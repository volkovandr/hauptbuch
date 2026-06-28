/**
 * Shared kernel — cross-cutting types every module may use freely.
 *
 * <p>Declared an {@code OPEN} Spring Modulith module so that depending on it from
 * any feature module is allowed and not flagged by {@code ApplicationModules.verify()}.
 * Keep this package small and stable: it is the one place a dependency from every
 * module is sanctioned, so anything that lands here is effectively API for the whole
 * app. The money type lives here because money is a concept the entire ledger shares
 * (CLAUDE.md §1.4, data-model §3.0).
 */
@org.springframework.modulith.ApplicationModule(
		displayName = "Shared",
		type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package volkovandr.hauptbuch.shared;
