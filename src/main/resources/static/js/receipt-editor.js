/*
 * Receipt image editor — the second bespoke-JS leaf (tech-stack §5, CLAUDE.md §1.6), a thin
 * component over the vendored Cropper.js library. Loaded ONLY on the processing screen (at the foot
 * of receipt-process.html), after cropper.min.js.
 *
 * DESIGN CONTRACT (read before touching): like the keyboard leaf, this is an ISOLATED, markup-driven
 * component — not a framework, never imported elsewhere. All image work is client-side (the Pi does
 * no image math): crop · rotate · tilt · skew · grayscale · brightness · contrast, previewed live on
 * the canvas and BAKED into a JPEG on Save. It attaches to a single [data-receipt-process] page via
 * data-attributes; remove this script (and cropper.min.js) and the read views + Discard/Delete forms
 * still work — only the in-page editor is lost.
 *
 * The flow (receipt doc §6.1):
 *   • "Prepare for analysis" / "Edit" (data-receipt-edit-start) enters edit mode: the read view and
 *     the common chrome are hidden, the Cropper editor form is shown, and — for a re-edit — the
 *     saved recipe (crop/rotation/tilt/skew/filters) is replayed onto the ORIGINAL image.
 *   • Rotation is RELATIVE (cropper.rotate(±90) for the buttons, a delta for the tilt slider) so it
 *     composes on top of the EXIF orientation Cropper applied — never snapping back to 0.
 *   • Skew is a vertical shear: previewed by shearing the whole Cropper composite, baked as a
 *     post-crop canvas shear. Crop first, then skew (the shear throws off pointer math while non-0).
 *   • Save always bakes (even zero adjustments — the edited copy is where EXIF-upright is made
 *     physical): the cropped canvas is redrawn through the filter and shear, downscaled so its long
 *     edge is ≤ 1568 px (never upscaled), exported as JPEG q≈0.9, and posted with the recipe JSON
 *     and the AI note as one multipart form (native navigation follows the redirect).
 *   • Cancel restores the read view (confirm-guarded when there are unsaved changes).
 */
(function () {
  "use strict";

  var LONG_EDGE_CAP = 1568; // The Anthropic API downscales beyond this anyway (receipt doc §6.1).
  var JPEG_QUALITY = 0.9;
  var INITIAL_CROP_AREA = 0.25; // A small central box to drag out from, not the full frame (§6.1).

  var root = document.querySelector("[data-receipt-process]");
  var form = root && root.querySelector("[data-receipt-edit]");
  var img = form && form.querySelector("[data-receipt-cropper]");
  // No editor on this view (a downstream state, or the leaf is absent) — nothing to wire.
  if (!root || !form || !img || typeof Cropper === "undefined") return;

  var readView = root.querySelector("[data-receipt-read]");
  var chrome = root.querySelector("[data-receipt-chrome]");
  var stage = form.querySelector(".receipt-edit__stage");
  var recipeField = form.querySelector("[data-receipt-recipe-field]");
  var imageField = form.querySelector("[data-receipt-image-field]");
  var downscaleNote = form.querySelector("[data-receipt-downscale]");

  var tools = {
    tilt: form.querySelector("[data-crop-tilt]"),
    skew: form.querySelector("[data-crop-skew]"),
    brightness: form.querySelector("[data-crop-brightness]"),
    contrast: form.querySelector("[data-crop-contrast]"),
    grayscale: form.querySelector("[data-crop-grayscale]"),
  };

  var cropper = null;
  var dirty = false;
  // Tilt is an ABSOLUTE slider but Cropper.rotate is relative, so we apply the delta since last move.
  var prevTilt = 0;

  // The saved recipe to replay on a re-edit (absent/blank for a fresh `new` receipt).
  var savedRecipe = parseRecipe(form.getAttribute("data-receipt-recipe"));

  function parseRecipe(text) {
    if (!text || !text.trim()) return null;
    try {
      return JSON.parse(text);
    } catch (e) {
      return null; // A malformed recipe just starts the editor fresh.
    }
  }

  // ── Filter + skew preview ────────────────────────────────────────────────────
  function filterString() {
    return (
      "grayscale(" +
      (tools.grayscale.checked ? 1 : 0) +
      ") brightness(" +
      pct(tools.brightness.value) +
      ") contrast(" +
      pct(tools.contrast.value) +
      ")"
    );
  }

  function pct(value) {
    return (Number(value) || 100) / 100;
  }

  function applyFilterPreview() {
    stage.style.setProperty("--receipt-filter", filterString());
  }

  function applySkewPreview() {
    stage.style.setProperty("--receipt-skew", (Number(tools.skew.value) || 0) + "deg");
  }

  function clampChannel(value) {
    return value < 0 ? 0 : value > 255 ? 255 : value;
  }

  /**
   * Bakes grayscale/brightness/contrast into `canvas`'s own pixels, in place. `ctx.filter` (the CSS
   * Filter Effects string built by filterString()) is Chrome/Firefox-only — Safari has never
   * implemented the canvas-context filter API, so it silently no-ops there (issue 12). These are the
   * same three W3C Filter Effects formulas, applied manually via getImageData/putImageData, which is
   * universally supported. Order and per-stage clamping match the native filter chain: grayscale
   * (Rec. 709 luma), then brightness (per-channel multiply), then contrast (per-channel scale around
   * the 128 midpoint).
   */
  function bakeFilters(canvas) {
    var brightness = pct(tools.brightness.value);
    var contrast = pct(tools.contrast.value);
    var grayscale = tools.grayscale.checked;
    if (!grayscale && brightness === 1 && contrast === 1) return; // Neutral — leave pixels untouched.

    var ctx = canvas.getContext("2d");
    var imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
    var data = imageData.data;
    for (var i = 0; i < data.length; i += 4) {
      var r = data[i];
      var g = data[i + 1];
      var b = data[i + 2];

      if (grayscale) {
        var luma = clampChannel(0.2126 * r + 0.7152 * g + 0.0722 * b);
        r = g = b = luma;
      }

      r = clampChannel(r * brightness);
      g = clampChannel(g * brightness);
      b = clampChannel(b * brightness);

      r = clampChannel((r - 128) * contrast + 128);
      g = clampChannel((g - 128) * contrast + 128);
      b = clampChannel((b - 128) * contrast + 128);

      data[i] = r;
      data[i + 1] = g;
      data[i + 2] = b;
    }
    ctx.putImageData(imageData, 0, 0);
  }

  /** Show the "downscaled for the AI" note when the crop's long edge exceeds the cap. */
  function showDownscaleNote(width, height) {
    if (!downscaleNote) return;
    var longest = Math.max(width || 0, height || 0);
    downscaleNote.toggleAttribute("hidden", longest <= LONG_EDGE_CAP);
  }

  function rotateBy(degrees) {
    if (cropper && degrees) cropper.rotate(degrees);
  }

  function markDirty() {
    dirty = true;
  }

  // ── Enter / leave edit mode ────────────────────────────────────────────────
  function startEdit() {
    if (readView) readView.setAttribute("hidden", "");
    if (chrome) chrome.setAttribute("hidden", "");
    form.removeAttribute("hidden"); // Un-hide BEFORE init so Cropper measures a real box.

    cropper = new Cropper(img, {
      viewMode: 1,
      autoCropArea: INITIAL_CROP_AREA,
      checkOrientation: true, // Bake in EXIF orientation — feed Cropper upright pixels (9b guard).
      background: true,
      responsive: true,
      // Live "will be downscaled" note: the crop box's own pixels tell us the bake's long edge, so
      // the user sees it while adjusting (a post-save note would vanish on the redirect).
      crop: function (event) {
        showDownscaleNote(event.detail.width, event.detail.height);
      },
      ready: function () {
        if (savedRecipe) replay(savedRecipe);
        applyFilterPreview();
        applySkewPreview();
      },
    });
  }

  function replay(recipe) {
    prevTilt = Number(recipe.tilt) || 0;
    tools.tilt.value = prevTilt;
    tools.skew.value = Number(recipe.skew) || 0;
    if (typeof recipe.brightness === "number") tools.brightness.value = recipe.brightness;
    if (typeof recipe.contrast === "number") tools.contrast.value = recipe.contrast;
    tools.grayscale.checked = Boolean(recipe.grayscale);
    // setData restores the absolute rotation (EXIF + coarse + tilt, all folded into crop.rotate) and
    // the crop box — so no separate rotate call is needed on replay.
    if (recipe.crop) cropper.setData(recipe.crop);
  }

  function cancelEdit() {
    if (dirty && !window.confirm("Discard your changes to this scan?")) return;
    destroy();
    form.setAttribute("hidden", "");
    if (readView) readView.removeAttribute("hidden");
    if (chrome) chrome.removeAttribute("hidden");
  }

  function destroy() {
    if (cropper) {
      cropper.destroy();
      cropper = null;
    }
    dirty = false;
  }

  // ── Save: bake the edited JPEG (crop → filter → shear) and submit ────────────
  function save() {
    if (!cropper) return;
    var cropped = cropper.getCroppedCanvas();
    if (!cropped) return;
    bakeFilters(cropped); // Manual pixel bake — works on Safari, unlike ctx.filter (issue 12).

    var longest = Math.max(cropped.width, cropped.height);
    var scale = longest > LONG_EDGE_CAP ? LONG_EDGE_CAP / longest : 1; // Never upscale.
    var outWidth = Math.max(1, Math.round(cropped.width * scale));
    var outHeight = Math.max(1, Math.round(cropped.height * scale));

    // Vertical shear: y' = y + shear·x (origin at the left edge, matching the CSS skewY preview).
    var shear = Math.tan(((Number(tools.skew.value) || 0) * Math.PI) / 180);
    var extra = Math.abs(shear) * outWidth;

    var out = document.createElement("canvas");
    out.width = outWidth;
    out.height = Math.ceil(outHeight + extra);
    var ctx = out.getContext("2d");
    // White ground so the triangular gaps the shear opens aren't black in the (alpha-less) JPEG.
    ctx.fillStyle = "#fff";
    ctx.fillRect(0, 0, out.width, out.height);
    ctx.translate(0, shear < 0 ? extra : 0);
    ctx.transform(1, shear, 0, 1, 0, 0);
    ctx.drawImage(cropped, 0, 0, outWidth, outHeight);

    out.toBlob(
      function (blob) {
        if (!blob) return;
        var file = new File([blob], "edited.jpg", { type: "image/jpeg" });
        var transfer = new DataTransfer();
        transfer.items.add(file);
        imageField.files = transfer.files;
        recipeField.value = JSON.stringify(currentRecipe());
        // Native submit → PRG redirect → the (now pre_processed) processing screen.
        if (form.requestSubmit) form.requestSubmit();
        else form.submit();
      },
      "image/jpeg",
      JPEG_QUALITY
    );
  }

  function currentRecipe() {
    // `crop.rotate` already carries the full rotation (EXIF + buttons + tilt); tilt/skew are stored
    // so the sliders and the shear can be replayed onto the original.
    return {
      crop: cropper.getData(true),
      tilt: Number(tools.tilt.value || 0),
      skew: Number(tools.skew.value || 0),
      brightness: Number(tools.brightness.value || 100),
      contrast: Number(tools.contrast.value || 100),
      grayscale: tools.grayscale.checked,
    };
  }

  // ── Wiring ──────────────────────────────────────────────────────────────────
  on(root, "click", "[data-receipt-edit-start]", function (e) {
    e.preventDefault();
    startEdit();
  });
  on(form, "click", "[data-receipt-cancel]", function (e) {
    e.preventDefault();
    cancelEdit();
  });
  on(form, "click", "[data-receipt-save]", function (e) {
    e.preventDefault();
    save();
  });
  on(form, "click", "[data-crop-rotate-left]", function () {
    rotateBy(-90);
    markDirty();
  });
  on(form, "click", "[data-crop-rotate-right]", function () {
    rotateBy(90);
    markDirty();
  });
  on(form, "input", "[data-crop-tilt]", function () {
    var value = Number(tools.tilt.value || 0);
    rotateBy(value - prevTilt); // Apply only the change, so the 90° steps are preserved.
    prevTilt = value;
    markDirty();
  });
  on(form, "input", "[data-crop-skew]", function () {
    applySkewPreview();
    markDirty();
  });
  on(form, "input", "[data-crop-brightness], [data-crop-contrast]", function () {
    applyFilterPreview();
    markDirty();
  });
  on(form, "change", "[data-crop-grayscale]", function () {
    applyFilterPreview();
    markDirty();
  });
  on(form, "click", "[data-crop-reset]", function () {
    if (cropper) cropper.reset(); // Back to the EXIF-upright orientation + central crop box.
    prevTilt = 0;
    tools.tilt.value = 0;
    tools.skew.value = 0;
    tools.brightness.value = 100;
    tools.contrast.value = 150;
    tools.grayscale.checked = true;
    applyFilterPreview();
    applySkewPreview();
    markDirty();
  });
  // Editing the AI note counts as an unsaved change too.
  var note = form.querySelector("[data-receipt-ainote]");
  if (note) note.addEventListener("input", markDirty);

  /** Delegated listener: run handler when the event target is within `selector` inside `scope`. */
  function on(scope, type, selector, handler) {
    scope.addEventListener(type, function (event) {
      var match = event.target.closest && event.target.closest(selector);
      if (match && scope.contains(match)) handler(event);
    });
  }
})();
