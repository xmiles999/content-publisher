package io.contentpublisher.platform.infrastructure.web;

import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.beta.InlineContentParser;
import org.commonmark.parser.beta.InlineContentParserFactory;
import org.commonmark.parser.beta.InlineParserState;
import org.commonmark.parser.beta.ParsedInline;

import java.util.Set;

/** Recognizes plain bold labels whose closing punctuation directly precedes Chinese text. */
final class ChineseBoldLabelParser implements InlineContentParserFactory {
    @Override
    public Set<Character> getTriggerCharacters() {
        return Set.of('*');
    }

    @Override
    public InlineContentParser create() {
        return this::tryParse;
    }

    private ParsedInline tryParse(InlineParserState state) {
        var scanner = state.scanner();
        if (scanner.peekPreviousCodePoint() == '*' || !scanner.next("**")
                || Character.isWhitespace(scanner.peek()) || Character.isSpaceChar(scanner.peek())) {
            return ParsedInline.none();
        }
        var start = scanner.position();
        while (scanner.hasNext()) {
            // Leave escapes, nested formatting, entities and links to CommonMark.
            char current = scanner.peek();
            if ("\\`_[]<>&\n".indexOf(current) >= 0) return ParsedInline.none();
            if (current == '*') {
                var end = scanner.position();
                int previous = scanner.peekPreviousCodePoint();
                if (!isPunctuation(previous) || !scanner.next("**") || scanner.peek() == '*'
                        || Character.UnicodeScript.of(scanner.peekCodePoint()) != Character.UnicodeScript.HAN) {
                    return ParsedInline.none();
                }
                var strong = new StrongEmphasis();
                strong.appendChild(new Text(scanner.getSource(start, end).getContent()));
                return ParsedInline.of(strong, scanner.position());
            }
            scanner.next();
        }
        return ParsedInline.none();
    }

    private boolean isPunctuation(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }
}
