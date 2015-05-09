/**
 * Copyright (c) 2008-2014, http://www.snakeyaml.org and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.yaml.snakeyaml.scanner;

import static org.yaml.snakeyaml.scanner.ScannerImpl.ESCAPE_CODES;
import static org.yaml.snakeyaml.scanner.ScannerImpl.ESCAPE_REPLACEMENTS;
import static org.yaml.snakeyaml.scanner.CScannerConstants.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.tokens.AliasToken;
import org.yaml.snakeyaml.tokens.AnchorToken;
import org.yaml.snakeyaml.tokens.BlockEndToken;
import org.yaml.snakeyaml.tokens.BlockEntryToken;
import org.yaml.snakeyaml.tokens.BlockMappingStartToken;
import org.yaml.snakeyaml.tokens.BlockSequenceStartToken;
import org.yaml.snakeyaml.tokens.DirectiveToken;
import org.yaml.snakeyaml.tokens.DocumentEndToken;
import org.yaml.snakeyaml.tokens.DocumentStartToken;
import org.yaml.snakeyaml.tokens.FlowEntryToken;
import org.yaml.snakeyaml.tokens.FlowMappingEndToken;
import org.yaml.snakeyaml.tokens.FlowMappingStartToken;
import org.yaml.snakeyaml.tokens.FlowSequenceEndToken;
import org.yaml.snakeyaml.tokens.FlowSequenceStartToken;
import org.yaml.snakeyaml.tokens.KeyToken;
import org.yaml.snakeyaml.tokens.ScalarToken;
import org.yaml.snakeyaml.tokens.StreamEndToken;
import org.yaml.snakeyaml.tokens.StreamStartToken;
import org.yaml.snakeyaml.tokens.TagToken;
import org.yaml.snakeyaml.tokens.TagTuple;
import org.yaml.snakeyaml.tokens.Token;
import org.yaml.snakeyaml.tokens.ValueToken;
import org.yaml.snakeyaml.util.ArrayStack;


/**
 * Custom variant of ScannerImpl.
 * 
 * Scanner produces tokens of the following types:<pre>
 * STREAM-START
 * STREAM-END
 * DIRECTIVE(name, value)
 * DOCUMENT-START
 * DOCUMENT-END
 * BLOCK-SEQUENCE-START
 * BLOCK-MAPPING-START
 * BLOCK-END
 * FLOW-SEQUENCE-START
 * FLOW-MAPPING-START
 * FLOW-SEQUENCE-END
 * FLOW-MAPPING-END
 * BLOCK-ENTRY
 * FLOW-ENTRY
 * KEY
 * VALUE
 * ALIAS(value)
 * ANCHOR(value)
 * TAG(value)
 * SCALAR(value, plain, style)
 * </pre>
 * 
 * Differences to ScannerImpl:
 * <ul>
 *     <li>The scanner allows to disable creation of content strings (see constructor).</li>
 *     <li>The scanner doesn't throw {@link ScannerException}, but reports errors to
 *         {@link #handleSyntaxProblem(String, Mark, String, Mark, String)} and continues.
 *     </li>
 *     <li>This implementation additionally reports comments to {@link #handleComment(int, int)}.
 *     </li>
 * </ul>
 */
public class CScannerImpl {
	
	private final static boolean isHex(final char ch) {
		return ((ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'F') || (ch >= 'a' && ch <= 'f'));
	}
	
	
    private final StreamReader reader;
    // Had we reached the end of the stream?
    private boolean done = false;

    // The number of unclosed '{' and '['. `flow_level == 0` means block
    // context.
    private int flowLevel = 0;

    // List of processed tokens that are not yet emitted.
    private final List<Token> tokens= new ArrayList<>(64);

    // Number of tokens that were emitted through the `get_token` method.
    private int tokensTaken = 0;

    // The current indentation level.
    private int indent = -1;

    // Past indentation levels.
    private final ArrayStack<Integer> indents= new ArrayStack<>(16);

    private final StringBuilder tmpSB= new StringBuilder(256);
    private final StringBuilder tmpSB2= new StringBuilder();
    private int tmpInt;

    private boolean createTagText;
    private boolean createAnchorText;
    private boolean createScalarText;

    // Variables related to simple keys treatment. See PyYAML.

    /**
     * <pre>
     * A simple key is a key that is not denoted by the '?' indicator.
     * Example of simple keys:
     *   ---
     *   block simple key: value
     *   ? not a simple key:
     *   : { flow simple key: value }
     * We emit the KEY token before all keys, so when we find a potential
     * simple key, we try to locate the corresponding ':' indicator.
     * Simple keys should be limited to a single line and 1024 characters.
     * 
     * Can a simple key start at the current position? A simple key may
     * start:
     * - at the beginning of the line, not counting indentation spaces
     *       (in block context),
     * - after '{', '[', ',' (in the flow context),
     * - after '?', ':', '-' (in the block context).
     * In the block context, this flag also signifies if a block collection
     * may start at the current position.
     * </pre>
     */
    private boolean allowSimpleKey = true;

    /*
     * Keep track of possible simple keys. This is a dictionary. The key is
     * `flow_level`; there can be no more that one possible simple key for each
     * level. The value is a SimpleKey record: (token_number, required, index,
     * line, column, mark) A simple key may start with ALIAS, ANCHOR, TAG,
     * SCALAR(flow), '[', or '{' tokens.
     */
    private Map<Integer, SimpleKey> possibleSimpleKeys;

    public CScannerImpl(StreamReader reader) {
        this(reader, true, true, true);
    }
    
    public CScannerImpl(StreamReader reader,
            final boolean createTagText, final boolean createAnchorText, final boolean createScalarText) {
        this.reader = reader;
        // The order in possibleSimpleKeys is kept for nextPossibleSimpleKey()
        this.possibleSimpleKeys = new LinkedHashMap<>();
        
        this.createTagText= createTagText;
        this.createAnchorText= createAnchorText;
        this.createScalarText= createScalarText;
        
        fetchStreamStart();// Add the STREAM-START token.
    }


	public void setCreateTagText(final boolean enable) {
		this.createTagText= enable;
	}
	
	public boolean getCreateTagText() {
		return this.createTagText;
	}
	
	public void setCreateAnchorText(final boolean enable) {
		this.createAnchorText= enable;
	}
	
	public boolean getCreateAnchorText() {
		return this.createAnchorText;
	}
	
	public void setCreateScalarText(final boolean enable) {
		this.createScalarText= enable;
	}
	
	public boolean getCreateScalarText() {
		return this.createScalarText;
	}
	
	public void setCreateText(final boolean enable) {
		this.createTagText= enable;
		this.createAnchorText= enable;
		this.createScalarText= enable;
	}
	
	
    public void reset(final String s, final int index) {
        this.reader.reset(s, index);
        this.done= false;
        this.flowLevel= 0;
        this.tokens.clear();
        this.tokensTaken = 0;
        this.indent = -1;
        this.indents.clear();
        this.allowSimpleKey= true;
        
        fetchStreamStart();// Add the STREAM-START token.
    }
    
    /**
     * Return the next token.
     */
    public Token nextToken() {
        while (needMoreTokens()) {
            fetchMoreTokens();
        }
        if (!this.tokens.isEmpty()) {
            this.tokensTaken++;
            return this.tokens.remove(0);
        }
        return null;
    }

    /**
     * Return the next token.
     */
    public boolean checkToken(final Token.ID tokenId) {
        while (needMoreTokens()) {
            fetchMoreTokens();
        }
        if (!this.tokens.isEmpty()) {
            return (this.tokens.get(0).getTokenId() == tokenId);
        }
        return false;
    }

    // Private methods.
    /**
     * Returns true if more tokens should be scanned.
     */
    private boolean needMoreTokens() {
        // If we are done, we do not require more tokens.
        if (this.done) {
            return false;
        }
        // If we aren't done, but we have no tokens, we need to scan more.
        if (this.tokens.isEmpty()) {
            return true;
        }
        // The current token may be a potential simple key, so we
        // need to look further.
        stalePossibleSimpleKeys();
        return nextPossibleSimpleKey() == this.tokensTaken;
    }

    /**
     * Fetch one or more tokens from the StreamReader.
     */
    private void fetchMoreTokens() {
        // Eat whitespaces and comments until we reach the next token.
        scanToNextToken();
        // Remove obsolete possible simple keys.
        stalePossibleSimpleKeys();
        // Compare the current indentation and column. It may add some tokens
        // and decrease the current indentation level.
        unwindIndent(reader.getColumn());
        // Peek the next character, to decide what the next group of tokens
        // will look like.
        char ch = reader.peek();
        switch (ch) {
        case '\0':
            // Is it the end of stream?
            fetchStreamEnd();
            return;
        case '%':
            // Is it a directive?
            if (checkDirective()) {
                fetchDirective();
                return;
            }
            break;
        case '-':
            // Is it the document start?
            if (checkDocumentStart()) {
                fetchDocumentStart();
                return;
                // Is it the block entry indicator?
            } else if (checkBlockEntry()) {
                fetchBlockEntry();
                return;
            }
            break;
        case '.':
            // Is it the document end?
            if (checkDocumentEnd()) {
                fetchDocumentEnd();
                return;
            }
            break;
        // TODO support for BOM within a stream. (not implemented in PyYAML)
        case '[':
            // Is it the flow sequence start indicator?
            fetchFlowSequenceStart();
            return;
        case '{':
            // Is it the flow mapping start indicator?
            fetchFlowMappingStart();
            return;
        case ']':
            // Is it the flow sequence end indicator?
            fetchFlowSequenceEnd();
            return;
        case '}':
            // Is it the flow mapping end indicator?
            fetchFlowMappingEnd();
            return;
        case ',':
            // Is it the flow entry indicator?
            fetchFlowEntry();
            return;
            // see block entry indicator above
        case '?':
            // Is it the key indicator?
            if (checkKey()) {
                fetchKey();
                return;
            }
            break;
        case ':':
            // Is it the value indicator?
            if (checkValue()) {
                fetchValue();
                return;
            }
            break;
        case '*':
            // Is it an alias?
            fetchAlias();
            return;
        case '&':
            // Is it an anchor?
            fetchAnchor();
            return;
        case '!':
            // Is it a tag?
            fetchTag();
            return;
        case '|':
            // Is it a literal scalar?
            if (this.flowLevel == 0) {
                fetchLiteral();
                return;
            }
            break;
        case '>':
            // Is it a folded scalar?
            if (this.flowLevel == 0) {
                fetchFolded();
                return;
            }
            break;
        case '\'':
            // Is it a single quoted scalar?
            fetchSingle();
            return;
        case '"':
            // Is it a double quoted scalar?
            fetchDouble();
            return;
        }
        // It must be a plain scalar then.
        if (checkPlain()) {
            fetchPlain();
            return;
        }
        
        // No? It's an error.
        newScannerException(SCANNING_FOR_NEXT_TOKEN, null, UNEXPECTED_CHAR,
                getCharPresentation(ch), null, reader.getMark() );
        this.reader.forward(1);
    }
    
    
    private String getCharPresentation(final char ch) {
        // Let's produce a nice error message.We do this by
        // converting escaped characters into their escape sequences. This is a
        // backwards use of the ESCAPE_REPLACEMENTS map.
        String chRepresentation= String.valueOf(ch);
        for (final Map.Entry<Character, String> entry : ESCAPE_REPLACEMENTS.entrySet()) {
            if (entry.getValue().equals(chRepresentation)) {
                return "\\" + entry.getKey();
            }
        }
        return chRepresentation;
    }

    // Simple keys treatment.

    /**
     * Return the number of the nearest possible simple key. Actually we don't
     * need to loop through the whole dictionary.
     */
    private int nextPossibleSimpleKey() {
        /*
         * the implementation is not as in PyYAML. Because
         * this.possibleSimpleKeys is ordered we can simply take the first key
         */
        if (!this.possibleSimpleKeys.isEmpty()) {
            return this.possibleSimpleKeys.values().iterator().next().getTokenNumber();
        }
        return -1;
    }

    /**
     * <pre>
     * Remove entries that are no longer possible simple keys. According to
     * the YAML specification, simple keys
     * - should be limited to a single line,
     * - should be no longer than 1024 characters.
     * Disabling this procedure will allow simple keys of any length and
     * height (may cause problems if indentation is broken though).
     * </pre>
     */
    private void stalePossibleSimpleKeys() {
        if (!this.possibleSimpleKeys.isEmpty()) {
            for (Iterator<SimpleKey> iterator = this.possibleSimpleKeys.values().iterator(); iterator
                    .hasNext();) {
                SimpleKey key = iterator.next();
                if ((key.getLine() != reader.getLine())
                        || (reader.getIndex() - key.getIndex() > 1024)) {
                    // If the key is not on the same line as the current
                    // position OR the difference in column between the token
                    // start and the current position is more than the maximum
                    // simple key length, then this cannot be a simple key.
                    if (key.isRequired()) {
                        // If the key was required, this implies an error
                        // condition.
                        newScannerException(SCANNING_SIMPLE_KEY, key.getMark(),
                                MISSING_MAP_COLON, reader.getMark() );
                    }
                    iterator.remove();
                }
            }
        }
    }

    /**
     * The next token may start a simple key. We check if it's possible and save
     * its position. This function is called for ALIAS, ANCHOR, TAG,
     * SCALAR(flow), '[', and '{'.
     */
    private void savePossibleSimpleKey() {
        // The next token may start a simple key. We check if it's possible
        // and save its position. This function is called for
        // ALIAS, ANCHOR, TAG, SCALAR(flow), '[', and '{'.

        // Check if a simple key is required at the current position.
        // A simple key is required if this position is the root flowLevel, AND
        // the current indentation level is the same as the last indent-level.
        boolean required = (this.flowLevel == 0 && this.indent == this.reader.getColumn());

        if (allowSimpleKey || !required) {
            // A simple key is required only if it is the first token in the
            // current line. Therefore it is always allowed.
        } else {
            throw new YAMLException(
                    "A simple key is required only if it is the first token in the current line");
        }

        // The next token might be a simple key. Let's save it's number and
        // position.
        if (this.allowSimpleKey) {
            Mark mark= this.reader.getMark();
            removePossibleSimpleKey(mark);
            int tokenNumber = this.tokensTaken + this.tokens.size();
            SimpleKey key = new SimpleKey(tokenNumber, required, reader.getIndex(),
                    reader.getLine(), this.reader.getColumn(), mark );
            this.possibleSimpleKeys.put(this.flowLevel, key);
        }
    }

    /**
     * Remove the saved possible key position at the current flow level.
     */
    private void removePossibleSimpleKey(Mark mark) {
        SimpleKey key = possibleSimpleKeys.remove(flowLevel);
        if (key != null && key.isRequired()) {
            newScannerException(SCANNING_SIMPLE_KEY, key.getMark(), MISSING_MAP_COLON, mark);
        }
    }

    // Indentation functions.

    /**
     * * Handle implicitly ending multiple levels of block nodes by decreased
     * indentation. This function becomes important on lines 4 and 7 of this
     * example:
     * 
     * <pre>
     * 1) book one:
     * 2)   part one:
     * 3)     chapter one
     * 4)   part two:
     * 5)     chapter one
     * 6)     chapter two
     * 7) book two:
     * </pre>
     * 
     * In flow context, tokens should respect indentation. Actually the
     * condition should be `self.indent &gt;= column` according to the spec. But
     * this condition will prohibit intuitively correct constructions such as
     * key : { } </pre>
     */
    private void unwindIndent(int col) {
        // In the flow context, indentation is ignored. We make the scanner less
        // restrictive then specification requires.
        if (this.flowLevel != 0) {
            return;
        }

        // In block context, we may need to issue the BLOCK-END tokens.
        while (this.indent > col) {
            Mark mark = reader.getMark();
            this.indent = this.indents.pop();
            this.tokens.add(new BlockEndToken(mark, mark));
        }
    }

    /**
     * Check if we need to increase indentation.
     */
    private boolean addIndent(int column) {
        if (this.indent < column) {
            this.indents.push(this.indent);
            this.indent = column;
            return true;
        }
        return false;
    }

    // Fetchers.

    /**
     * We always add STREAM-START as the first token and STREAM-END as the last
     * token.
     */
    private void fetchStreamStart() {
        // Read the token.
        Mark mark = reader.getMark();

        // Add STREAM-START.
        Token token = new StreamStartToken(mark, mark);
        this.tokens.add(token);
    }

    private void fetchStreamEnd() {
        // Set the current intendation to -1.
        unwindIndent(-1);

        Mark mark = reader.getMark();

        // Reset simple keys.
        removePossibleSimpleKey(mark);
        this.allowSimpleKey = false;
        this.possibleSimpleKeys.clear();

        // Add STREAM-END.
        Token token = new StreamEndToken(mark, mark);
        this.tokens.add(token);

        // The stream is finished.
        this.done = true;
    }

    /**
     * Fetch a YAML directive. Directives are presentation details that are
     * interpreted as instructions to the processor. YAML defines two kinds of
     * directives, YAML and TAG; all other types are reserved for future use.
     * 
     * @see http://www.yaml.org/spec/1.1/#id864824
     */
    private void fetchDirective() {
        // Set the current intendation to -1.
        unwindIndent(-1);

        Mark startMark = reader.getMark();
        
        // Reset simple keys.
        removePossibleSimpleKey(startMark);
        this.allowSimpleKey = false;

        // Scan and add DIRECTIVE.
        Token tok = scanDirective(startMark);
        this.tokens.add(tok);
    }

    /**
     * Fetch a document-start token ("---").
     */
    private void fetchDocumentStart() {
        fetchDocumentIndicator(true);
    }

    /**
     * Fetch a document-end token ("...").
     */
    private void fetchDocumentEnd() {
        fetchDocumentIndicator(false);
    }

    /**
     * Fetch a document indicator, either "---" for "document-start", or else
     * "..." for "document-end. The type is chosen by the given boolean.
     */
    private void fetchDocumentIndicator(boolean isDocumentStart) {
        // Set the current intendation to -1.
        unwindIndent(-1);

        Mark startMark = reader.getMark();
        
        // Reset simple keys. Note that there could not be a block collection
        // after '---'.
        removePossibleSimpleKey(startMark);
        this.allowSimpleKey = false;

        // Add DOCUMENT-START or DOCUMENT-END.
        reader.forward(3);
        Mark endMark = reader.getMark();
        Token token;
        if (isDocumentStart) {
            token = new DocumentStartToken(startMark, endMark);
        } else {
            token = new DocumentEndToken(startMark, endMark);
        }
        this.tokens.add(token);
    }

    private void fetchFlowSequenceStart() {
        fetchFlowCollectionStart(false);
    }

    private void fetchFlowMappingStart() {
        fetchFlowCollectionStart(true);
    }

    /**
     * Fetch a flow-style collection start, which is either a sequence or a
     * mapping. The type is determined by the given boolean.
     * 
     * A flow-style collection is in a format similar to JSON. Sequences are
     * started by '[' and ended by ']'; mappings are started by '{' and ended by
     * '}'.
     * 
     * @see http://www.yaml.org/spec/1.1/#id863975
     * 
     * @param isMappingStart
     */
    private void fetchFlowCollectionStart(boolean isMappingStart) {
        // '[' and '{' may start a simple key.
        savePossibleSimpleKey();

        // Increase the flow level.
        this.flowLevel++;

        // Simple keys are allowed after '[' and '{'.
        this.allowSimpleKey = true;

        // Add FLOW-SEQUENCE-START or FLOW-MAPPING-START.
        Mark startMark = reader.getMark();
        reader.forward(1);
        Mark endMark = reader.getMark();
        Token token;
        if (isMappingStart) {
            token = new FlowMappingStartToken(startMark, endMark);
        } else {
            token = new FlowSequenceStartToken(startMark, endMark);
        }
        this.tokens.add(token);
    }

    private void fetchFlowSequenceEnd() {
        fetchFlowCollectionEnd(false);
    }

    private void fetchFlowMappingEnd() {
        fetchFlowCollectionEnd(true);
    }

    /**
     * Fetch a flow-style collection end, which is either a sequence or a
     * mapping. The type is determined by the given boolean.
     * 
     * A flow-style collection is in a format similar to JSON. Sequences are
     * started by '[' and ended by ']'; mappings are started by '{' and ended by
     * '}'.
     * 
     * @see http://www.yaml.org/spec/1.1/#id863975
     */
    private void fetchFlowCollectionEnd(boolean isMappingEnd) {
        Mark startMark = reader.getMark();
        
        // Reset possible simple key on the current level.
        removePossibleSimpleKey(startMark);

        // Decrease the flow level.
        this.flowLevel--;

        // No simple keys after ']' or '}'.
        this.allowSimpleKey = false;

        // Add FLOW-SEQUENCE-END or FLOW-MAPPING-END.
        reader.forward();
        Mark endMark = reader.getMark();
        Token token;
        if (isMappingEnd) {
            token = new FlowMappingEndToken(startMark, endMark);
        } else {
            token = new FlowSequenceEndToken(startMark, endMark);
        }
        this.tokens.add(token);
    }

    /**
     * Fetch an entry in the flow style. Flow-style entries occur either
     * immediately after the start of a collection, or else after a comma.
     * 
     * @see http://www.yaml.org/spec/1.1/#id863975
     */
    private void fetchFlowEntry() {
        Mark startMark = reader.getMark();
        
        // Simple keys are allowed after ','.
        this.allowSimpleKey = true;

        // Reset possible simple key on the current level.
        removePossibleSimpleKey(startMark);

        // Add FLOW-ENTRY.
        reader.forward();
        Mark endMark = reader.getMark();
        Token token = new FlowEntryToken(startMark, endMark);
        this.tokens.add(token);
    }

    /**
     * Fetch an entry in the block style.
     * 
     * @see http://www.yaml.org/spec/1.1/#id863975
     */
    private void fetchBlockEntry() {
        Mark startMark = reader.getMark();
        // Block context needs additional checks.
        if (this.flowLevel == 0) {
            // Are we allowed to start a new entry?
            if (!this.allowSimpleKey) {
                newScannerException((byte) 0, startMark, UNEXPECTED_BLOCK_SEQ_ENTRY, startMark);
            }

            // We may need to add BLOCK-SEQUENCE-START.
            if (addIndent(this.reader.getColumn())) {
                this.tokens.add(new BlockSequenceStartToken(startMark, startMark));
            }
        } else {
            // It's an error for the block entry to occur in the flow
            // context,but we let the parser detect this.
        }
        // Simple keys are allowed after '-'.
        this.allowSimpleKey = true;

        // Reset possible simple key on the current level.
        removePossibleSimpleKey(startMark);

        // Add BLOCK-ENTRY.
        reader.forward();
        Mark endMark = reader.getMark();
        Token token = new BlockEntryToken(startMark, endMark);
        this.tokens.add(token);
    }

    /**
     * Fetch a key in a block-style mapping.
     * 
     * @see http://www.yaml.org/spec/1.1/#id863975
     */
    private void fetchKey() {
        Mark startMark = reader.getMark();
        
        // Block context needs additional checks.
        if (this.flowLevel == 0) {
            // Are we allowed to start a key (not necessary a simple)?
            if (!this.allowSimpleKey) {
                newScannerException((byte) 0, startMark, UNEXPECTED_MAP_KEY, startMark);
            }
            // We may need to add BLOCK-MAPPING-START.
            if (addIndent(this.reader.getColumn())) {
                Mark mark = reader.getMark();
                this.tokens.add(new BlockMappingStartToken(mark, mark));
            }
        }
        // Simple keys are allowed after '?' in the block context.
        this.allowSimpleKey = this.flowLevel == 0;

        // Reset possible simple key on the current level.
        removePossibleSimpleKey(startMark);

        // Add KEY.
        reader.forward();
        Mark endMark = reader.getMark();
        Token token = new KeyToken(startMark, endMark);
        this.tokens.add(token);
    }

    /**
     * Fetch a value in a block-style mapping.
     * 
     * @see http://www.yaml.org/spec/1.1/#id863975
     */
    private void fetchValue() {
        Mark startMark = reader.getMark();
        
        // Do we determine a simple key?
        SimpleKey key = this.possibleSimpleKeys.remove(this.flowLevel);
        if (key != null) {
            // Add KEY.
            this.tokens.add(key.getTokenNumber() - this.tokensTaken, new KeyToken(key.getMark(),
                    key.getMark()));

            // If this key starts a new block mapping, we need to add
            // BLOCK-MAPPING-START.
            if (this.flowLevel == 0) {
                if (addIndent(key.getColumn())) {
                    this.tokens.add(key.getTokenNumber() - this.tokensTaken,
                            new BlockMappingStartToken(key.getMark(), key.getMark()));
                }
            }
            // There cannot be two simple keys one after another.
            this.allowSimpleKey = false;

        } else {
            // It must be a part of a complex key.
            // Block context needs additional checks. Do we really need them?
            // They will be caught by the parser anyway.
            if (this.flowLevel == 0) {

                // We are allowed to start a complex value if and only if we can
                // start a simple key.
                if (!this.allowSimpleKey) {
                    newScannerException((byte) 0, startMark, UNEXPECTED_MAP_VALUE, startMark);
                }
            }

            // If this value starts a new block mapping, we need to add
            // BLOCK-MAPPING-START. It will be detected as an error later by
            // the parser.
            if (flowLevel == 0) {
                if (addIndent(reader.getColumn())) {
                    Mark mark = reader.getMark();
                    this.tokens.add(new BlockMappingStartToken(mark, mark));
                }
            }

            // Simple keys are allowed after ':' in the block context.
            allowSimpleKey = (flowLevel == 0);

            // Reset possible simple key on the current level.
            removePossibleSimpleKey(startMark);
        }
        // Add VALUE.
        reader.forward();
        Mark endMark = reader.getMark();
        Token token = new ValueToken(startMark, endMark);
        this.tokens.add(token);
    }

    /**
     * Fetch an alias, which is a reference to an anchor. Aliases take the
     * format:
     * 
     * <pre>
     * *(anchor name)
     * </pre>
     * 
     * @see http://www.yaml.org/spec/1.1/#id863390
     */
    private void fetchAlias() {
        // ALIAS could be a simple key.
        savePossibleSimpleKey();

        // No simple keys after ALIAS.
        this.allowSimpleKey = false;

        // Scan and add ALIAS.
        Token tok = scanAnchor(SCANNING_ALIAS);
        this.tokens.add(tok);
    }

    /**
     * Fetch an anchor. Anchors take the form:
     * 
     * <pre>
     * &(anchor name)
     * </pre>
     * 
     * @see http://www.yaml.org/spec/1.1/#id863390
     */
    private void fetchAnchor() {
        // ANCHOR could start a simple key.
        savePossibleSimpleKey();

        // No simple keys after ANCHOR.
        this.allowSimpleKey = false;

        // Scan and add ANCHOR.
        Token tok = scanAnchor(SCANNING_ANCHOR);
        this.tokens.add(tok);
    }

    /**
     * Fetch a tag. Tags take a complex form.
     * 
     * @see http://www.yaml.org/spec/1.1/#id861700
     */
    private void fetchTag() {
        // TAG could start a simple key.
        savePossibleSimpleKey();

        // No simple keys after TAG.
        this.allowSimpleKey = false;

        // Scan and add TAG.
        Token tok = scanTag();
        this.tokens.add(tok);
    }

    /**
     * Fetch a literal scalar, denoted with a vertical-bar. This is the type
     * best used for source code and other content, such as binary data, which
     * must be included verbatim.
     * 
     * @see http://www.yaml.org/spec/1.1/#id863975
     */
    private void fetchLiteral() {
        fetchBlockScalar('|');
    }

    /**
     * Fetch a folded scalar, denoted with a greater-than sign. This is the type
     * best used for long content, such as the text of a chapter or description.
     * 
     * @see http://www.yaml.org/spec/1.1/#id863975
     */
    private void fetchFolded() {
        fetchBlockScalar('>');
    }

    /**
     * Fetch a block scalar (literal or folded).
     * 
     * @see http://www.yaml.org/spec/1.1/#id863975
     * 
     * @param style
     */
    private void fetchBlockScalar(char style) {
        Mark startMark = reader.getMark();
        
        // A simple key may follow a block scalar.
        this.allowSimpleKey = true;

        // Reset possible simple key on the current level.
        removePossibleSimpleKey(startMark);

        // Scan and add SCALAR.
        Token tok = scanBlockScalar(style, startMark);
        this.tokens.add(tok);
    }

    /**
     * Fetch a single-quoted (') scalar.
     */
    private void fetchSingle() {
        fetchFlowScalar(SCANNING_SQUOTED_SCALAR);
    }

    /**
     * Fetch a double-quoted (") scalar.
     */
    private void fetchDouble() {
        fetchFlowScalar(SCANNING_DQUOTED_SCALAR);
    }

    /**
     * Fetch a flow scalar (single- or double-quoted).
     * 
     * @see http://www.yaml.org/spec/1.1/#id863975
     * 
     * @param style
     */
    private void fetchFlowScalar(final byte context) {
        // A flow scalar could be a simple key.
        savePossibleSimpleKey();

        // No simple keys after flow scalars.
        this.allowSimpleKey = false;

        // Scan and add SCALAR.
        Token tok = scanFlowScalar(context);
        this.tokens.add(tok);
    }

    /**
     * Fetch a plain scalar.
     */
    private void fetchPlain() {
        // A plain scalar could be a simple key.
        savePossibleSimpleKey();

        // No simple keys after plain scalars. But note that `scan_plain` will
        // change this flag if the scan is finished at the beginning of the
        // line.
        this.allowSimpleKey = false;

        // Scan and add SCALAR. May change `allow_simple_key`.
        Token tok = scanPlain();
        this.tokens.add(tok);
    }

    // Checkers.
    /**
     * Returns true if the next thing on the reader is a directive, given that
     * the leading '%' has already been checked.
     * 
     * @see http://www.yaml.org/spec/1.1/#id864824
     */
    private boolean checkDirective() {
        // DIRECTIVE: ^ '%' ...
        // The '%' indicator is already checked.
        return reader.getColumn() == 0;
    }

    /**
     * Returns true if the next thing on the reader is a document-start ("---").
     * A document-start is always followed immediately by a new line.
     */
    private boolean checkDocumentStart() {
        // DOCUMENT-START: ^ '---' (' '|'\n')
        if (reader.getColumn() == 0) {
            if ("---".equals(reader.prefix(3)) && Constant.NULL_BL_T_LINEBR.has(reader.peek(3))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the next thing on the reader is a document-end ("..."). A
     * document-end is always followed immediately by a new line.
     */
    private boolean checkDocumentEnd() {
        // DOCUMENT-END: ^ '...' (' '|'\n')
        if (reader.getColumn() == 0) {
            if ("...".equals(reader.prefix(3)) && Constant.NULL_BL_T_LINEBR.has(reader.peek(3))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the next thing on the reader is a block token.
     */
    private boolean checkBlockEntry() {
        // BLOCK-ENTRY: '-' (' '|'\n')
        return Constant.NULL_BL_T_LINEBR.has(reader.peek(1));
    }

    /**
     * Returns true if the next thing on the reader is a key token.
     */
    private boolean checkKey() {
        // KEY(flow context): '?'
        if (this.flowLevel != 0) {
            return true;
        } else {
            // KEY(block context): '?' (' '|'\n')
            return Constant.NULL_BL_T_LINEBR.has(reader.peek(1));
        }
    }

    /**
     * Returns true if the next thing on the reader is a value token.
     */
    private boolean checkValue() {
        // VALUE(flow context): ':'
        if (flowLevel != 0) {
            return true;
        } else {
            // VALUE(block context): ':' (' '|'\n')
            return Constant.NULL_BL_T_LINEBR.has(reader.peek(1));
        }
    }

    /**
     * Returns true if the next thing on the reader is a plain token.
     */
    private boolean checkPlain() {
        /**
         * <pre>
         * A plain scalar may start with any non-space character except:
         *   '-', '?', ':', ',', '[', ']', '{', '}',
         *   '#', '&amp;', '*', '!', '|', '&gt;', '\'', '\&quot;',
         *   '%', '@', '`'.
         * 
         * It may also start with
         *   '-', '?', ':'
         * if it is followed by a non-space character.
         * 
         * Note that we limit the last rule to the block context (except the
         * '-' character) because we want the flow context to be space
         * independent.
         * </pre>
         */
        char ch = reader.peek();
        // If the next char is NOT one of the forbidden chars above or
        // whitespace, then this is the start of a plain scalar.
        return Constant.NULL_BL_T_LINEBR.hasNo(ch, "-?:,[]{}#&*!|>\'\"%@`")
                || (Constant.NULL_BL_T_LINEBR.hasNo(reader.peek(1)) && (ch == '-' || (this.flowLevel == 0 && "?:"
                        .indexOf(ch) != -1)));
    }

    // Scanners.

    /**
     * <pre>
     * We ignore spaces, line breaks and comments.
     * If we find a line break in the block context, we set the flag
     * `allow_simple_key` on.
     * The byte order mark is stripped if it's the first character in the
     * stream. We do not yet support BOM inside the stream as the
     * specification requires. Any such mark will be considered as a part
     * of the document.
     * TODO: We need to make tab handling rules more sane. A good rule is
     *   Tabs cannot precede tokens
     *   BLOCK-SEQUENCE-START, BLOCK-MAPPING-START, BLOCK-END,
     *   KEY(block), VALUE(block), BLOCK-ENTRY
     * So the checking code is
     *   if &lt;TAB&gt;:
     *       self.allow_simple_keys = False
     * We also need to add the check for `allow_simple_keys == True` to
     * `unwind_indent` before issuing BLOCK-END.
     * Scanners for block, flow, and plain scalars need to be modified.
     * </pre>
     */
    private void scanToNextToken() {
        // If there is a byte order mark (BOM) at the beginning of the stream,
        // forward past it.
        if (reader.getIndex() == 0 && reader.peek() == '\uFEFF') {
            reader.forward();
        }
        boolean found = false;
        while (!found) {
            int ff = 0;
            // Peek ahead until we find the first non-space character, then
            // move forward directly to that character.
            while (reader.peek(ff) == ' ') {
                ff++;
            }
            if (ff > 0) {
                reader.forward(ff);
            }
            // If the character we have skipped forward to is a comment (#),
            // then peek ahead until we find the next end of line. YAML
            // comments are from a # to the next new-line. We then forward
            // past the comment.
            if (reader.peek() == '#') {
                forwardComment();
            }
            // If we scanned a line break, then (depending on flow level),
            // simple keys may be allowed.
            if (scanLineBreak().length() != 0) {// found a line-break
                if (this.flowLevel == 0) {
                    // Simple keys are allowed at flow-level 0 after a line
                    // break
                    this.allowSimpleKey = true;
                }
            } else {
                found = true;
            }
        }
    }

    /**
     * Called if a the start of a comment ('#') was found
     */
    private void forwardComment() {
        final int beginIndex= this.reader.getIndex();
        int length = 1;
        while (Constant.NULL_OR_LINEBR.hasNo(reader.peek(length))) {
            length++;
        }
        reader.forward(length);
        handleComment(beginIndex, this.reader.getIndex());
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Token scanDirective(final Mark startMark) {
        // See the specification for details.
        Mark endMark;
        reader.forward();
        
        String name = scanDirectiveName(startMark);
        if (name != null) {
            if (Constant.NULL_BL_LINEBR.hasNo(reader.peek())) {
                newScannerException(SCANNING_DIRECTIVE, startMark, UNEXPECTED_CHAR,
                        reader.peek(), null, reader.getMark());
            }
        }
        
        List<?> value = null;
        if ("YAML".equals(name)) {
            value = scanYamlDirectiveValue(startMark);
            endMark = reader.getMark();
            scanIgnoredLineTail(SCANNING_DIRECTIVE, startMark);
        } else if ("TAG".equals(name)) {
            value = scanTagDirectiveValue(startMark);
            endMark = reader.getMark();
            scanIgnoredLineTail(SCANNING_DIRECTIVE, startMark);
        } else {
            endMark = reader.getMark();
            forwardToLineEnd();
        }
        return new DirectiveToken(name, value, startMark, endMark);
    }
    
    private void forwardToLineEnd() {
        int ff = 0;
        while (Constant.NULL_OR_LINEBR.hasNo(reader.peek(ff))) {
            ff++;
        }
        if (ff > 0) {
            reader.forward(ff);
        }
    }

    /**
     * Scan a directive name. Directive names are a series of non-space
     * characters.
     * 
     * @see http://www.yaml.org/spec/1.1/#id895217
     */
    private String scanDirectiveName(Mark startMark) {
        // See the specification for details.
        char ch;
        int length = 0;
        // A Directive-name is a sequence of alphanumeric characters
        // (a-z,A-Z,0-9). We scan until we find something that isn't.
        // FIXME this disagrees with the specification.
        while (Constant.ALPHA.has(ch= reader.peek(length))) {
            length++;
        }
        // If the name would be empty, an error occurs.
        if (length == 0) {
            newScannerException(SCANNING_DIRECTIVE, startMark, MISSING_DIRECTIVE_NAME,
                    reader.getMark() );
            return null;
        }
        return reader.prefixForward(length);
    }

    private List<Integer> scanYamlDirectiveValue(Mark startMark) {
        // See the specification for details.
        while (reader.peek() == ' ') {
            reader.forward();
        }
        Integer major = scanYamlDirectiveNumber(startMark);
        Integer minor;
        if (reader.peek() != '.') {
            newScannerException(SCANNING_YAML_DIRECTIVE, startMark, UNEXPECTED_CHAR_FOR_VERSION_NUMBER,
                    reader.peek(), null, reader.getMark() );
            minor= null;
        }
        else {
            reader.forward();
            minor = scanYamlDirectiveNumber(startMark);
            if (Constant.NULL_BL_LINEBR.hasNo(reader.peek())) {
                newScannerException(SCANNING_YAML_DIRECTIVE, startMark, UNEXPECTED_CHAR_FOR_VERSION_NUMBER,
                        reader.peek(), null, reader.getMark() );
            }
        }
        List<Integer> result = new ArrayList<>(2);
        result.add(major);
        result.add(minor);
        return result;
    }

    /**
     * Read a %YAML directive number: this is either the major or the minor
     * part. Stop reading at a non-digit character (usually either '.' or '\n').
     * 
     * @see http://www.yaml.org/spec/1.1/#id895631
     * @see http://www.yaml.org/spec/1.1/#ns-dec-digit
     */
    private Integer scanYamlDirectiveNumber(Mark startMark) {
        // See the specification for details.
        char ch;
        int length = 0;
        while ((ch= reader.peek(length)) >= '0' && ch <= '9') {
            length++;
        }
        if (length == 0) {
            newScannerException(SCANNING_YAML_DIRECTIVE, startMark, UNEXPECTED_CHAR_FOR_VERSION_NUMBER,
                    ch, null, reader.getMark() );
            return null;
        }
        Integer value = Integer.parseInt(reader.prefixForward(length));
        return value;
    }

    /**
     * <p>
     * Read a %TAG directive value:
     * 
     * <pre>
     * s-ignored-space+ c-tag-handle s-ignored-space+ ns-tag-prefix s-l-comments
     * </pre>
     * 
     * </p>
     * 
     * @see http://www.yaml.org/spec/1.1/#id896044
     */
    private List<String> scanTagDirectiveValue(Mark startMark) {
        // See the specification for details.
        while (reader.peek() == ' ') {
            reader.forward();
        }
        String handle = scanTagDirectiveHandle(startMark);
        while (reader.peek() == ' ') {
            reader.forward();
        }
        String prefix = scanTagDirectivePrefix(startMark);
        if (createTagText) {
            List<String> result = new ArrayList<>(2);
            result.add(handle);
            result.add(prefix);
            return result;
        }
        return null;
    }

    /**
     * Scan a %TAG directive's handle. This is YAML's c-tag-handle.
     * 
     * @see http://www.yaml.org/spec/1.1/#id896876
     * @param startMark
     * @return
     */
    private String scanTagDirectiveHandle(Mark startMark) {
        // See the specification for details.
        String value = scanTagHandle(SCANNING_TAG_DIRECTIVE, startMark);
        if (reader.peek() != ' ') {
            newScannerException(SCANNING_TAG_DIRECTIVE, startMark, UNEXPECTED_CHAR_2,
                    reader.peek(), " ", reader.getMark() );
        }
        return value;
    }

    /**
     * Scan a %TAG directive's prefix. This is YAML's ns-tag-prefix.
     * 
     * @see http://www.yaml.org/spec/1.1/#ns-tag-prefix
     */
    private String scanTagDirectivePrefix(Mark startMark) {
        // See the specification for details.
        String value = scanTagUri(SCANNING_TAG_DIRECTIVE, startMark);
        if (Constant.NULL_BL_LINEBR.hasNo(reader.peek())) {
            newScannerException(SCANNING_TAG_DIRECTIVE, startMark, UNEXPECTED_CHAR_2,
                    reader.peek(), " ", reader.getMark() );
        }
        return value;
    }

    private String scanIgnoredLineTail(final byte context, Mark startMark) {
        // See the specification for details.
        char ch;
        int ff = 0;
        while (reader.peek(ff) == ' ') {
            ff++;
        }
        if (ff > 0) {
            reader.forward(ff);
        }
        if (reader.peek() == '#') {
            forwardComment();
        }
        if (Constant.NULL_OR_LINEBR.hasNo(ch= reader.peek())) {
            newScannerException(context, startMark, UNEXPECTED_CHAR, ch, null, reader.getMark());
            forwardToLineEnd();
        }
        return scanLineBreak();
    }

    /**
     * <pre>
     * The specification does not restrict characters for anchors and
     * aliases. This may lead to problems, for instance, the document:
     *   [ *alias, value ]
     * can be interpreted in two ways, as
     *   [ &quot;value&quot; ]
     * and
     *   [ *alias , &quot;value&quot; ]
     * Therefore we restrict aliases to numbers and ASCII letters.
     * </pre>
     */
    private Token scanAnchor(final byte context) {
        Mark startMark = reader.getMark();
        char ch;
        /*char indicator = */reader.peek();
        reader.forward();
        int length = 0;
        while (Constant.ALPHA.has(ch= reader.peek(length))) {
            length++;
        }
        String value= null;
        if (length == 0) {
            newScannerException(context, startMark, MISSING_ANCHOR_NAME, reader.getMark());
        }
        else {
            if (this.createAnchorText) {
                value = reader.prefix(length);
            }
            reader.forward(length);
            if (Constant.NULL_BL_T_LINEBR.hasNo(ch= reader.peek(), "?:,]}%@`")) {
                newScannerException(context, startMark, UNEXPECTED_CHAR,
                        ch, null, reader.getMark() );
            }
        }
        Mark endMark = reader.getMark();
        Token tok;
        if (context == SCANNING_ANCHOR) {
            tok = new AnchorToken(value, startMark, endMark);
        } else {
            tok = new AliasToken(value, startMark, endMark);
        }
        return tok;
    }

    /**
     * <p>
     * Scan a Tag property. A Tag property may be specified in one of three
     * ways: c-verbatim-tag, c-ns-shorthand-tag, or c-ns-non-specific-tag
     * </p>
     * 
     * <p>
     * c-verbatim-tag takes the form !&lt;ns-uri-char+&gt; and must be delivered
     * verbatim (as-is) to the application. In particular, verbatim tags are not
     * subject to tag resolution.
     * </p>
     * 
     * <p>
     * c-ns-shorthand-tag is a valid tag handle followed by a non-empty suffix.
     * If the tag handle is a c-primary-tag-handle ('!') then the suffix must
     * have all exclamation marks properly URI-escaped (%21); otherwise, the
     * string will look like a named tag handle: !foo!bar would be interpreted
     * as (handle="!foo!", suffix="bar").
     * </p>
     * 
     * <p>
     * c-ns-non-specific-tag is always a lone '!'; this is only useful for plain
     * scalars, where its specification means that the scalar MUST be resolved
     * to have type tag:yaml.org,2002:str.
     * </p>
     * 
     * TODO SnakeYaml incorrectly ignores c-ns-non-specific-tag right now.
     * 
     * @see http://www.yaml.org/spec/1.1/#id900262
     * 
     *      TODO Note that this method does not enforce rules about local versus
     *      global tags!
     */
    private Token scanTag() {
        // See the specification for details.
        Mark startMark = reader.getMark();
        char ch;
        // Determine the type of tag property based on the first character
        // encountered
        ch = reader.peek(1);
        String handle = null;
        String suffix = null;
        // Verbatim tag! (c-verbatim-tag)
        if (ch == '<') {
            // Skip the exclamation mark and &gt;, then read the tag suffix (as
            // a URI).
            reader.forward(2);
            suffix = scanTagUri(SCANNING_TAG, startMark);
            if (reader.peek() != '>') {
                // If there are any characters between the end of the tag-suffix
                // URI and the closing &gt;, then an error has occurred.
                newScannerException(SCANNING_TAG, startMark, UNEXPECTED_CHAR_2,
                        reader.peek(), ">", reader.getMark() );
            }
            else {
                reader.forward();
            }
        } else if (Constant.NULL_BL_T_LINEBR.has(ch)) {
            // A NUL, blank, tab, or line-break means that this was a
            // c-ns-non-specific tag.
            suffix = "!";
            reader.forward();
        } else {
            // Any other character implies c-ns-shorthand-tag type.

            // Look ahead in the stream to determine whether this tag property
            // is of the form !foo or !foo!bar.
            int length = 1;
            boolean useHandle = false;
            while (Constant.NULL_BL_LINEBR.hasNo(ch)) {
                if (ch == '!') {
                    useHandle = true;
                    break;
                }
                length++;
                ch = reader.peek(length);
            }
            handle = "!";
            // If we need to use a handle, scan it in; otherwise, the handle is
            // presumed to be '!'.
            if (useHandle) {
                handle = scanTagHandle(SCANNING_TAG, startMark);
            } else {
                handle = "!";
                reader.forward();
            }
            suffix = scanTagUri(SCANNING_TAG, startMark);
        }
        // Check that the next character is allowed to follow a tag-property;
        // if it is not, raise the error.
        if (Constant.NULL_BL_LINEBR.hasNo(ch= reader.peek())) {
            newScannerException(SCANNING_TAG, startMark, UNEXPECTED_CHAR_2,
                    ch, " ", reader.getMark() );
        }
        TagTuple value = new TagTuple(handle, suffix);
        Mark endMark = reader.getMark();
        return new TagToken(value, startMark, endMark);
    }

    private Token scanBlockScalar(char style, Mark startMark) {
        // See the specification for details.
        boolean folded;
        // Depending on the given style, we determine whether the scalar is
        // folded ('>') or literal ('|')
        if (style == '>') {
            folded = true;
        } else {
            folded = false;
        }
        StringBuilder chunks = getScalarSB();
        // Scan the header.
        reader.forward();
        Chomping chompi = scanBlockScalarIndicators(startMark);
        int increment = chompi.getIncrement();
        scanIgnoredLineTail(SCANNING_BLOCK_SCALAR, startMark);

        // Determine the indentation level and go to the first non-empty line.
        int minIndent = this.indent + 1;
        if (minIndent < 1) {
            minIndent = 1;
        }
        int maxIndent = 0;
        int indent = 0;
        Mark endMark;
        if (increment == -1) {
            endMark = scanBlockScalarIndentation();
            maxIndent = tmpInt;
            indent = Math.max(minIndent, maxIndent);
        } else {
            indent = minIndent + increment - 1;
            endMark = scanBlockScalarBreaks(indent);
        }

        String lineBreak = "";

        // Scan the inner part of the block scalar.
        while (this.reader.getColumn() == indent && reader.peek() != '\0') {
            if (chunks != null) {
                chunks.append(getBreaks());
            }
            boolean leadingNonSpace = " \t".indexOf(reader.peek()) == -1;
            int length = 0;
            while (Constant.NULL_OR_LINEBR.hasNo(reader.peek(length))) {
                length++;
            }
            if (chunks != null) {
                chunks.append(reader.prefix(length));
            }
            reader.forward(length);
            lineBreak = scanLineBreak();
            endMark = scanBlockScalarBreaks(indent);
            if (this.reader.getColumn() == indent && reader.peek() != '\0') {
                if (chunks != null) {
                    // Unfortunately, folding rules are ambiguous.
                    //
                    // This is the folding according to the specification:
                    if (folded && "\n".equals(lineBreak) && leadingNonSpace
                            && " \t".indexOf(reader.peek()) == -1) {
                        if (getBreaks().length() == 0) {
                            chunks.append(" ");
                        }
                    } else {
                        chunks.append(lineBreak);
                    }
                }
                // Clark Evans's interpretation (also in the spec examples) not
                // imported from PyYAML
            } else {
                break;
            }
        }
        // Chomp the tail.
        if (chunks != null) {
            if (chompi.chompTailIsNotFalse()) {
                chunks.append(lineBreak);
            }
            if (chompi.chompTailIsTrue()) {
                chunks.append(getBreaks());
            }
        }
        // We are done.
        return new ScalarToken((chunks != null) ? chunks.toString() : null, false, startMark, endMark, style);
    }

    /**
     * Scan a block scalar indicator. The block scalar indicator includes two
     * optional components, which may appear in either order.
     * 
     * A block indentation indicator is a non-zero digit describing the
     * indentation level of the block scalar to follow. This indentation is an
     * additional number of spaces relative to the current indentation level.
     * 
     * A block chomping indicator is a + or -, selecting the chomping mode away
     * from the default (clip) to either -(strip) or +(keep).
     * 
     * @see http://www.yaml.org/spec/1.1/#id868988
     * @see http://www.yaml.org/spec/1.1/#id927035
     * @see http://www.yaml.org/spec/1.1/#id927557
     */
    private Chomping scanBlockScalarIndicators(Mark startMark) {
        // See the specification for details.
        Boolean chomping = null;
        int increment = -1;
        char ch = reader.peek();
        if (ch == '-' || ch == '+') {
            if (ch == '+') {
                chomping = Boolean.TRUE;
            } else {
                chomping = Boolean.FALSE;
            }
            reader.forward();
            ch = reader.peek();
            if (ch >= '1' && ch <= '9') {
                increment = Integer.parseInt(String.valueOf(ch));
                reader.forward();
            }
        } else if (ch >= '1' && ch <= '9') {
            increment = Integer.parseInt(String.valueOf(ch));
            reader.forward();
            ch = reader.peek();
            if (ch == '-' || ch == '+') {
                if (ch == '+') {
                    chomping = Boolean.TRUE;
                } else {
                    chomping = Boolean.FALSE;
                }
                reader.forward();
            }
        }
        
        return new Chomping(chomping, increment);
    }

    /**
     * Scans for the indentation of a block scalar implicitly. This mechanism is
     * used only if the block did not explicitly state an indentation to be
     * used.
     * 
     * @see http://www.yaml.org/spec/1.1/#id927035
     */
    private Mark scanBlockScalarIndentation() {
        // See the specification for details.
        StringBuilder chunks = getBreaksSB();
        int maxIndent = 0;
        Mark endMark = reader.getMark();
        // Look ahead some number of lines until the first non-blank character
        // occurs; the determined indentation will be the maximum number of
        // leading spaces on any of these lines.
        while (Constant.LINEBR.has(reader.peek(), " \r")) {
            if (reader.peek() != ' ') {
                // If the character isn't a space, it must be some kind of
                // line-break; scan the line break and track it.
                chunks.append(scanLineBreak());
                endMark = reader.getMark();
            } else {
                // If the character is a space, move forward to the next
                // character; if we surpass our previous maximum for indent
                // level, update that too.
                reader.forward();
                if (this.reader.getColumn() > maxIndent) {
                    maxIndent = reader.getColumn();
                }
            }
        }
        // Pass several results back together.
        this.tmpInt= maxIndent;
        return endMark;
    }

    private Mark scanBlockScalarBreaks(int indent) {
        // See the specification for details.
        StringBuilder chunks = getBreaksSB();
        Mark endMark = reader.getMark();
        int ff = 0;
        int col = this.reader.getColumn();
        // Scan for up to the expected indentation-level of spaces, then move
        // forward past that amount.
        while (col < indent && reader.peek(ff) == ' ') {
            ff++;
            col++;
        }
        if (ff > 0) {
            reader.forward(ff);
        }
        // Consume one or more line breaks followed by any amount of spaces,
        // until we find something that isn't a line-break.
        String lineBreak = null;
        while ((lineBreak = scanLineBreak()).length() != 0) {
            chunks.append(lineBreak);
            endMark = reader.getMark();
            // Scan past up to (indent) spaces on the next line, then forward
            // past them.
            ff = 0;
            col = this.reader.getColumn();
            while (col < indent && reader.peek(ff) == ' ') {
                ff++;
                col++;
            }
            if (ff > 0) {
                reader.forward(ff);
            }
        }
        // Return both the assembled intervening string and the end-mark.
        return endMark;
    }

    /**
     * Scan a flow-style scalar. Flow scalars are presented in one of two forms;
     * first, a flow scalar may be a double-quoted string; second, a flow scalar
     * may be a single-quoted string.
     * 
     * @see http://www.yaml.org/spec/1.1/#flow style/syntax
     * 
     *      <pre>
     * See the specification for details.
     * Note that we loose indentation rules for quoted scalars. Quoted
     * scalars don't need to adhere indentation because &quot; and ' clearly
     * mark the beginning and the end of them. Therefore we are less
     * restrictive then the specification requires. We only need to check
     * that document separators are not included in scalars.
     * </pre>
     */
    private Token scanFlowScalar(final byte context) {
        final boolean _double;
        // The style will be either single- or double-quoted; we determine this
        // by the first character in the entry (supplied)
        switch (context) {
        case SCANNING_DQUOTED_SCALAR:
            _double = true;
            break;
        case SCANNING_SQUOTED_SCALAR:
            _double = false;
            break;
        default:
            throw new IllegalStateException(Integer.toString(context));
        }
        StringBuilder chunks = getScalarSB();
        Mark startMark = reader.getMark();
        char quote = reader.peek();
        reader.forward();
        scanFlowScalarNonSpaces(_double, startMark, chunks);
        while (true) {
            if (reader.peek() == quote) {
                reader.forward();
                break;
            }
            if (!scanFlowScalarSpaces(context, startMark, chunks)) {
                newScannerException(context, startMark, NOT_CLOSED, reader.getMark());
                break;
            }
            scanFlowScalarNonSpaces(_double, startMark, chunks);
        }
        Mark endMark = reader.getMark();
        return new ScalarToken((chunks != null) ? chunks.toString() : null, false, startMark, endMark,
                (_double) ? '"' : '\'' );
    }

    /**
     * Scan some number of flow-scalar non-space characters.
     */
    private void scanFlowScalarNonSpaces(boolean doubleQuoted, Mark startMark, StringBuilder chunks) {
        // See the specification for details.
        while (true) {
            // Scan through any number of characters which are not: NUL, blank,
            // tabs, line breaks, single-quotes, double-quotes, or backslashes.
            int length = 0;
            while (Constant.NULL_BL_T_LINEBR.hasNo(reader.peek(length), "\'\"\\")) {
                length++;
            }
            if (length != 0) {
                if (chunks != null) {
                    chunks.append(reader.prefix(length));
                }
                reader.forward(length);
            }
            // Depending on our quoting-type, the characters ', " and \ have
            // differing meanings.
            char ch = reader.peek();
            if (!doubleQuoted && ch == '\'' && reader.peek(1) == '\'') {
                if (chunks != null) {
                    chunks.append("'");
                }
                reader.forward(2);
            } else if ((doubleQuoted && ch == '\'') || (!doubleQuoted && "\"\\".indexOf(ch) != -1)) {
                if (chunks != null) {
                    chunks.append(ch);
                }
                reader.forward();
            } else if (doubleQuoted && ch == '\\') {
                reader.forward();
                ch = reader.peek();
                final Character chObj= Character.valueOf(ch);
                if (ESCAPE_REPLACEMENTS.containsKey(chObj)) {
                    // The character is one of the single-replacement
                    // types; these are replaced with a literal character
                    // from the mapping.
                    reader.forward();
                    if (chunks != null) {
                        chunks.append(ESCAPE_REPLACEMENTS.get(chObj));
                    }
                } else if (ESCAPE_CODES.containsKey(chObj)) {
                    // The character is a multi-digit escape sequence, with
                    // length defined by the value in the ESCAPE_CODES map.
                    reader.forward();
                    int expLength = ESCAPE_CODES.get(chObj).intValue();
                    length= 0;
                    while (length < expLength) {
                        if (!isHex(ch= reader.peek(length))) {
                            break;
                        }
                        length++;
                    }
                    if (length != expLength) {
                        newScannerException(SCANNING_DQUOTED_SCALAR, startMark, UNEXPECTED_ESCAPE_SEQUENCE,
                                reader.prefix(length), null, reader.getMark() );
                        chunks= null;
                        // continue
                    }
                    else if (chunks != null) {
                        int codePoint= Integer.parseInt(reader.prefix(length), 16);
                        chunks.append(Character.toChars(codePoint));
                    }
                    reader.forward(length);
                } else if (scanLineBreak().length() != 0) {
                    scanFlowScalarBreaks(SCANNING_DQUOTED_SCALAR, startMark);
                    if (chunks != null) {
                        chunks.append(getBreaks());
                    }
                } else {
                    newScannerException(SCANNING_DQUOTED_SCALAR, startMark, UNEXPECTED_ESCAPE_SEQUENCE,
                            ch, null, reader.getMark() );
                    // continue
                }
            } else {
                return;
            }
        }
    }

    private boolean scanFlowScalarSpaces(final byte context, Mark startMark, StringBuilder chunks) {
        // See the specification for details.
        int length = 0;
        // Scan through any number of whitespace (space, tab) characters,
        // consuming them.
        while (" \t".indexOf(reader.peek(length)) != -1) {
            length++;
        }
        String whitespaces = (chunks != null) ? reader.prefix(length) : null;
        reader.forward(length);
        if (reader.peek() == '\0') {
            // A flow scalar cannot end with an end-of-stream
            return false;
        }
        // If we encounter a line break, scan it into our assembled string...
        String lineBreak = scanLineBreak();
        if (lineBreak.length() != 0) {
            if (!scanFlowScalarBreaks(context, startMark)) {
                return false;
            }
            if (chunks != null) {
                String breaks = getBreaks();
                if (!"\n".equals(lineBreak)) {
                    chunks.append(lineBreak);
                } else if (breaks.length() == 0) {
                    chunks.append(" ");
                }
                chunks.append(breaks);
            }
        } else {
            if (chunks != null) {
                chunks.append(whitespaces);
            }
        }
        return true;
    }

    private boolean scanFlowScalarBreaks(final byte context, Mark startMark) {
        // See the specification for details.
        StringBuilder chunks = getBreaksSB();
        while (true) {
            // Instead of checking indentation, we check for document
            // separators.
            String prefix = reader.prefix(3);
            if (("---".equals(prefix) || "...".equals(prefix))
                    && Constant.NULL_BL_T_LINEBR.has(reader.peek(3))) {
                return false;
            }
            // Scan past any number of spaces and tabs, ignoring them
            while (" \t".indexOf(reader.peek()) != -1) {
                reader.forward();
            }
            // If we stopped at a line break, add that; otherwise, return the
            // assembled set of scalar breaks.
            String lineBreak = scanLineBreak();
            if (lineBreak.length() != 0) {
                chunks.append(lineBreak);
            } else {
                return true;
            }
        }
    }

    /**
     * Scan a plain scalar.
     * 
     * <pre>
     * See the specification for details.
     * We add an additional restriction for the flow context:
     *   plain scalars in the flow context cannot contain ',', ':' and '?'.
     * We also keep track of the `allow_simple_key` flag here.
     * Indentation rules are loosed for the flow context.
     * </pre>
     */
    private Token scanPlain() {
        StringBuilder chunks = getScalarSB();
        Mark startMark = reader.getMark();
        Mark endMark = startMark;
        int indent = this.indent + 1;
        String spaces = "";
        while (true) {
            char ch;
            int length = 0;
            // A comment indicates the end of the scalar.
            if (reader.peek() == '#') {
                break;
            }
            while (true) {
                ch = reader.peek(length);
                if (Constant.NULL_BL_T_LINEBR.has(ch)
                        || (this.flowLevel == 0 && ch == ':' && Constant.NULL_BL_T_LINEBR
                                .has(reader.peek(length + 1)))
                        || (this.flowLevel != 0 && ",:?[]{}".indexOf(ch) != -1)) {
                    break;
                }
                length++;
            }
            // It's not clear what we should do with ':' in the flow context.
            // http://pyyaml.org/wiki/YAMLColonInFlowContext
            if (this.flowLevel != 0 && ch == ':'
                    && Constant.NULL_BL_T_LINEBR.hasNo(reader.peek(length + 1), ",[]{}")) {
                reader.forward(length);
                newScannerException(SCANNING_PLAIN_SCALAR, startMark, UNEXPECTED_CHAR, ":", null,
                        reader.getMark() );
                break;
            }
            if (length == 0) {
                break;
            }
            this.allowSimpleKey = false;
            if (chunks != null) {
                chunks.append(spaces);
                chunks.append(reader.prefix(length));
            }
            reader.forward(length);
            endMark = reader.getMark();
            spaces = scanPlainSpaces();
            // System.out.printf("spaces[%s]\n", spaces);
            if (spaces.length() == 0 || reader.peek() == '#'
                    || (this.flowLevel == 0 && this.reader.getColumn() < indent)) {
                break;
            }
        }
        return new ScalarToken((chunks != null) ? chunks.toString() : null, startMark, endMark, true);
    }

    /**
     * See the specification for details. SnakeYAML and libyaml allow tabs
     * inside plain scalar
     */
    private String scanPlainSpaces() {
        int length = 0;
        while (reader.peek(length) == ' ' || reader.peek(length) == '\t') {
            length++;
        }
        String whitespaces = reader.prefixForward(length);
        String lineBreak = scanLineBreak();
        if (lineBreak.length() != 0) {
            this.allowSimpleKey = true;
            String prefix = reader.prefix(3);
            if ("---".equals(prefix) || "...".equals(prefix)
                    && Constant.NULL_BL_T_LINEBR.has(reader.peek(3))) {
                return "";
            }
            StringBuilder breaks = getBreaksSB();
            while (true) {
                if (reader.peek() == ' ') {
                    reader.forward();
                } else {
                    String lb = scanLineBreak();
                    if (lb.length() != 0) {
                        breaks.append(lb);
                        prefix = reader.prefix(3);
                        if ("---".equals(prefix) || "...".equals(prefix)
                                && Constant.NULL_BL_T_LINEBR.has(reader.peek(3))) {
                            return "";
                        }
                    } else {
                        break;
                    }
                }
            }
            if (!"\n".equals(lineBreak)) {
                return lineBreak + breaks;
            } else if (breaks.length() == 0) {
                return " ";
            }
            return breaks.toString();
        }
        return whitespaces;
    }

    /**
     * <p>
     * Scan a Tag handle. A Tag handle takes one of three forms:
     * 
     * <pre>
     * "!" (c-primary-tag-handle)
     * "!!" (ns-secondary-tag-handle)
     * "!(name)!" (c-named-tag-handle)
     * </pre>
     * 
     * Where (name) must be formatted as an ns-word-char.
     * </p>
     * 
     * @see http://www.yaml.org/spec/1.1/#c-tag-handle
     * @see http://www.yaml.org/spec/1.1/#ns-word-char
     * 
     *      <pre>
     * See the specification for details.
     * For some strange reasons, the specification does not allow '_' in
     * tag handles. I have allowed it anyway.
     * </pre>
     */
    private String scanTagHandle(final byte context, Mark startMark) {
        char ch = reader.peek();
        if (ch != '!') {
            newScannerException(context, startMark, UNEXPECTED_CHAR, ch, "!", reader.getMark());
            return "";
        }
        // Look for the next '!' in the stream, stopping if we hit a
        // non-word-character. If the first character is a space, then the
        // tag-handle is a c-primary-tag-handle ('!').
        int length = 1;
        ch = reader.peek(length);
        String value = null;
        if (ch != ' ') {
            // Scan through 0+ alphabetic characters.
            // FIXME According to the specification, these should be
            // ns-word-char only, which prohibits '_'. This might be a
            // candidate for a configuration option.
            while (Constant.ALPHA.has(ch)) {
                length++;
                ch = reader.peek(length);
            }
            // Found the next non-word-char. If this is not a space and not an
            // '!', then this is an error, as the tag-handle was specified as:
            // !(name) or similar; the trailing '!' is missing.
            if (ch == '!') {
                length++;
            }
            else {
                newScannerException(context, startMark, UNEXPECTED_CHAR, ch, null, reader.getMark());
            }
        }
        if (this.createTagText) {
            value = reader.prefix(length);
        }
        reader.forward(length);
        return value;
    }

    /**
     * <p>
     * Scan a Tag URI. This scanning is valid for both local and global tag
     * directives, because both appear to be valid URIs as far as scanning is
     * concerned. The difference may be distinguished later, in parsing. This
     * method will scan for ns-uri-char*, which covers both cases.
     * </p>
     * 
     * <p>
     * This method performs no verification that the scanned URI conforms to any
     * particular kind of URI specification.
     * </p>
     * 
     * @see http://www.yaml.org/spec/1.1/#ns-uri-char
     */
    private String scanTagUri(final byte context, Mark startMark) {
        // See the specification for details.
        // Note: we do not check if URI is well-formed.
        // Scan through accepted URI characters, which includes the standard
        // URI characters, plus the start-escape character ('%'). When we get
        // to a start-escape, scan the escaped sequence, then return.
        int length = 0;
        while (Constant.URI_CHARS.has(reader.peek(length))) {
            length++;
        }
        if (length == 0) {
            // If no URI was found, an error has occurred.
            newScannerException(context, startMark, MISSING_URI, reader.getMark());
        }
        return reader.prefixForward(length);
    }

    /**
     * <p>
     * Scan a sequence of %-escaped URI escape codes and convert them into a
     * String representing the unescaped values.
     * </p>
     * 
     * FIXME This method fails for more than 256 bytes' worth of URI-encoded
     * characters in a row. Is this possible? Is this a use-case?
     * 
     * @see http://www.ietf.org/rfc/rfc2396.txt, section 2.4, Escaped Encoding.
     */
    // TODO validate uri

    /**
     * Scan a line break, transforming:
     * 
     * <pre>
     * '\r\n' : '\n'
     * '\r' : '\n'
     * '\n' : '\n'
     * '\x85' : '\n'
     * default : ''
     * </pre>
     */
    private String scanLineBreak() {
        // Transforms:
        // '\r\n' : '\n'
        // '\r' : '\n'
        // '\n' : '\n'
        // '\x85' : '\n'
        // default : ''
        char ch = reader.peek();
        if (ch == '\r' || ch == '\n' || ch == '\u0085') {
            if (ch == '\r' && '\n' == reader.peek(1)) {
                reader.forward(2);
            } else {
                reader.forward();
            }
            return "\n";
        } else if (ch == '\u2028' || ch == '\u2029') {
            reader.forward();
            return String.valueOf(ch);
        }
        return "";
    }

    /**
     * Chomping the tail may have 3 values - yes, no, not defined.
     */
    private static class Chomping {
        private final Boolean value;
        private final int increment;

        public Chomping(Boolean value, int increment) {
            this.value = value;
            this.increment = increment;
        }

        public boolean chompTailIsNotFalse() {
            return value == null || value;
        }

        public boolean chompTailIsTrue() {
            return value != null && value;
        }

        public int getIncrement() {
            return increment;
        }
    }


    private StringBuilder getScalarSB() {
        if (this.createScalarText) {
            this.tmpSB.setLength(0);
            return this.tmpSB;
        }
        return null;
    }

    private StringBuilder getBreaksSB() {
        this.tmpSB2.setLength(0);
        return this.tmpSB2;
    }

    private String getBreaks() {
    	return this.tmpSB2.toString();
    }

	private void newScannerException(final byte context, final Mark contextMark,
			final byte problem, final Mark problemMark) {
		handleSyntaxProblem(context, contextMark,
				problem, problemMark, null, null );
	}
	
	private void newScannerException(final byte context, final Mark contextMark,
			final byte problem, final String problemText, final String arg2, final Mark problemMark) {
		handleSyntaxProblem(context, contextMark,
				problem, problemMark, problemText, null );
	}
	
	private void newScannerException(final byte context, final Mark contextMark,
			final byte problem, final char problemText, final String arg2, final Mark problemMark) {
		handleSyntaxProblem(context, contextMark,
				problem, problemMark, String.valueOf(problemText), arg2 );
	}
	
	protected void handleSyntaxProblem(final byte context, final Mark contextMark,
			final byte problem, final Mark problemMark, final String problemText, final String arg2) {
		// overwrite
	}
	
	protected void handleComment(final int startIndex, final int endIndex) {
		// overwrite
	}
	
}
