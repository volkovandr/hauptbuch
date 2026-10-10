package volkovandr.hauptbuch.statements;

/**
 * Everything the {@link StatementParser} needs for one Messages-API call. ARCH-08: the prompt is
 * parsing instructions only and the user text is the statement being parsed — nothing from the
 * ledger.
 *
 * @param model the Anthropic model id (from {@code settings.ai_model})
 * @param apiKey the resolved API key
 * @param systemPrompt the parsing instructions and TOON skeleton
 * @param userText the profile's AI note followed by the statement text as the operator edited it
 */
record StatementParseRequest(String model, String apiKey, String systemPrompt, String userText) {}
