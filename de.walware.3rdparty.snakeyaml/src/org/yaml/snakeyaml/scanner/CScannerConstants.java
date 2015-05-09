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


public class CScannerConstants {
	
	// -- context --
	
	public static final byte SCANNING_FOR_NEXT_TOKEN= 1;
	public static final byte SCANNING_DIRECTIVE= 3;
	public static final byte SCANNING_YAML_DIRECTIVE= 4;
	public static final byte SCANNING_TAG_DIRECTIVE= 5;
	public static final byte SCANNING_SIMPLE_KEY= 7;
	public static final byte SCANNING_ANCHOR= 8;
	public static final byte SCANNING_ALIAS= 9;
	public static final byte SCANNING_TAG= 10;
	public static final byte SCANNING_BLOCK_SCALAR= 11;
	public static final byte SCANNING_SQUOTED_SCALAR= 12;
	public static final byte SCANNING_DQUOTED_SCALAR= 13;
	public static final byte SCANNING_PLAIN_SCALAR= 14;
	
	
	// -- problem --
	
	public static final byte UNEXPECTED_CHAR= 1; // 0= char
	public static final byte UNEXPECTED_CHAR_2= 2; // 0= char, 1= expected
	public static final byte UNEXPECTED_ESCAPE_SEQUENCE= 3;
	public static final byte NOT_CLOSED= 4;
	public static final byte MISSING_DIRECTIVE_NAME= 9;
	public static final byte UNEXPECTED_CHAR_FOR_VERSION_NUMBER= 10;
	public static final byte MISSING_URI= 11;
	public static final byte MISSING_ANCHOR_NAME= 12;
	public static final byte MISSING_MAP_COLON= 17;
	public static final byte UNEXPECTED_BLOCK_SEQ_ENTRY= 18;
	public static final byte UNEXPECTED_MAP_KEY= 19;
	public static final byte UNEXPECTED_MAP_VALUE= 20;
	
	
	public static String getString(final byte state) {
		switch (state) {
		case SCANNING_FOR_NEXT_TOKEN:
			return "while scanning for the next token";
		case SCANNING_SIMPLE_KEY:
			return "while scanning a simple key";
		case SCANNING_DIRECTIVE:
			return "while scanning a directive";
		case SCANNING_ANCHOR:
			return "while scanning an anchor";
		case SCANNING_ALIAS:
			return "while scanning an alias";
		case SCANNING_TAG:
			return "while scanning a tag";
		case SCANNING_BLOCK_SCALAR:
			return "while scanning a block scalar";
		case SCANNING_SQUOTED_SCALAR:
			return "while scanning a single-quoted scalar";
		case SCANNING_DQUOTED_SCALAR:
			return "while scanning a double-quoted scalar";
		case SCANNING_PLAIN_SCALAR:
			return "while scanning a plain scalar";
		default:
			return null;
		}
	}
	
}
