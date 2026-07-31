/*
 * Receipt image editor — the second bespoke-JS leaf (tech-stack §5, CLAUDE.md §1.6), a thin
 * component over the vendored Cropper.js library. Loaded ONLY on the processing screen (at the foot
 * of receipt-process.html), after cropper.min.js.
 *
 * DESIGN CONTRACT (read before touching): like the keyboard leaf, this is an ISOLATED, markup-driven
 * component — not a framework, never imported elsewhere. All image work is client-side (the Pi does
 * no image math): crop · rotate · tilt · grayscale · brightness · contrast, previewed live on the
 * canvas and BAKED into a JPEG on Save. It attaches to a single [data-receipt-process] page via
 * data-attributes; remove this script (and cropper.min.js) and the read views + Discard/Delete
 * forms still work — only the in-page editor is lost.
 *
 * The flow (receipt doc §6.1):
 *   • "Prepare for analysis" / "Edit" (data-receipt-edit-start) enters edit mode: the read view and
 *     the common chrome are hidden, the Cropper editor form is shown, and — for a re-edit — the
 *     saved recipe (crop/rotation/tilt/filters) is replayed onto the ORIGINAL image.
 *   • The tools drive Cropper (rotate/tilt) and a CSS filter (grayscale/brightness/contrast) for a
 *     live preview.
 *   • Save always bakes (even zero adjustments — the edited copy is where EXIF-upright is made
 *     physical): the cropped canvas is redrawn through the same filter, downscaled so its long edge
 *     is ≤ 1568 px (never upscaled), exported as JPEG q≈0.9, and posted together with the recipe
 *     JSON and the AI note as one multipart form (native navigation follows the redirect).
 *   • Cancel restores the read view (confirm-guarded when there are unsaved changes).
 */
(function () {
  "use strict";

  var LONG_EDGE_CAP = 1568; // The Anthropic API downscales beyond this anyway (receipt doc §6.1).
  var JPEG_QUALITY = 0.9;

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
    brightness: form.querySelector("[data-crop-brightness]"),
    contrast: form.querySelector("[data-crop-contrast]"),
    grayscale: form.querySelector("[data-crop-grayscale]"),
  };

  var cropper = null;
  var dirty = false;
  var rotation = 0; // The 90° steps from the rotate buttons; tilt is the fine slider on top.

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

  function applyRotation() {
    if (cropper) cropper.rotateTo(rotation + Number(tools.tilt.value || 0));
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
      autoCropArea: 1,
      checkOrientation: true, // Bake in EXIF orientation — feed Cropper upright pixels (9b guard).
      background: true,
      responsive: true,
      ready: function () {
        if (savedRecipe) replay(savedRecipe);
        else applyFilterPreview();
      },
    });
  }

  function replay(recipe) {
    rotation = Number(recipe.rotation) || 0;
    if (typeof recipe.tilt === "number") tools.tilt.value = recipe.tilt;
    if (typeof recipe.brightness === "number") tools.brightness.value = recipe.brightness;
    if (typeof recipe.contrast === "number") tools.contrast.value = recipe.contrast;
    tools.grayscale.checked = Boolean(recipe.grayscale);
    applyRotation();
    if (recipe.crop) cropper.setData(recipe.crop);
    applyFilterPreview();
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

  // ── Save: bake the edited JPEG and submit ───────────────────────────────────
  function save() {
    if (!cropper) return;
    var cropped = cropper.getCroppedCanvas();
    if (!cropped) return;

    var longest = Math.max(cropped.width, cropped.height);
    var scale = longest > LONG_EDGE_CAP ? LONG_EDGE_CAP / longest : 1; // Never upscale.
    var outWidth = Math.max(1, Math.round(cropped.width * scale));
    var outHeight = Math.max(1, Math.round(cropped.height * scale));

    var out = document.createElement("canvas");
    out.width = outWidth;
    out.height = outHeight;
    var ctx = out.getContext("2d");
    ctx.filter = filterString(); // Bake the same filters the preview showed.
    ctx.drawImage(cropped, 0, 0, outWidth, outHeight);

    if (downscaleNote) downscaleNote.toggleAttribute("hidden", scale === 1);

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
    return {
      crop: cropper.getData(true),
      rotation: rotation,
      tilt: Number(tools.tilt.value || 0),
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
    rotation = (rotation - 90) % 360;
    applyRotation();
    markDirty();
  });
  on(form, "click", "[data-crop-rotate-right]", function () {
    rotation = (rotation + 90) % 360;
    applyRotation();
    markDirty();
  });
  on(form, "input", "[data-crop-tilt]", function () {
    applyRotation();
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
    rotation = 0;
    tools.tilt.value = 0;
    tools.brightness.value = 100;
    tools.contrast.value = 100;
    tools.grayscale.checked = false;
    if (cropper) cropper.reset();
    applyRotation();
    applyFilterPreview();
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
