/**
 * Web module — the server-rendered UI shell every later screen hangs on (plan stage 4).
 *
 * <p>A Spring Modulith application module owning the cross-cutting UI <em>chrome</em>: the base
 * Thymeleaf layout, the navigation shell ({@link volkovandr.hauptbuch.web.NavItem}), the htmx
 * wiring, and the vendored bespoke-JS leaves (the keyboard layer and, later, the Cropper.js image
 * component — CLAUDE.md §1.6). Feature screens' controllers live in <em>their own</em> feature
 * modules (e.g. the settings UI and greeting landing in {@code ledger} at stage 5, the register at
 * stage 7); each depends on this module's {@code NavItem} — never the reverse, so the shell holds
 * no feature dependencies and stays free of cycles.
 *
 * <p>Not a layer-first package: it is a vertical slice for "the application shell" as a feature,
 * not an app-root {@code controllers/} bucket (CLAUDE.md §1.1). It may call other modules' public
 * top-level types; other modules do not reach into it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Web")
package volkovandr.hauptbuch.web;
