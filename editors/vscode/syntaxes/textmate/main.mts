import * as fs from 'node:fs';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';

type CaptureMap = Record<string, { name: string }>;

type Pattern = {
  name?: string;
  match?: string;
  begin?: string;
  end?: string;
  beginCaptures?: CaptureMap;
  endCaptures?: CaptureMap;
  captures?: CaptureMap;
  contentName?: string;
  patterns?: Array<Pattern | IncludePattern>;
};

type IncludePattern = {
  include: string;
};

type Grammar = {
  $schema: string;
  name: string;
  scopeName: string;
  patterns: IncludePattern[];
  repository: Record<string, Pattern>;
};

const IDENTIFIER = String.raw`[\p{XID_Start}_][\p{XID_Continue}_]*`;
const TYPE_NAME = String.raw`[A-Z][\p{XID_Continue}_]*`;

const blockComment: Pattern = {
  name: 'comment.block.design',
  begin: String.raw`\/\*`,
  end: String.raw`\*\/`,
  beginCaptures: {
    '0': {
      name: 'punctuation.definition.comment.design',
    },
  },
  endCaptures: {
    '0': {
      name: 'punctuation.definition.comment.design',
    },
  },
  patterns: [{ include: '#blockComment' }],
};

const lineComment: Pattern = {
  name: 'comment.line.double-slash.design',
  begin: String.raw`\/\/`,
  end: String.raw`(?=$|\n)`,
  beginCaptures: {
    '0': {
      name: 'punctuation.definition.comment.design',
    },
  },
};

const comments: Pattern = {
  patterns: [{ include: '#blockComment' }, { include: '#lineComment' }],
};

const escape: Pattern = {
  name: 'constant.character.escape.design',
  match: String.raw`\\.`,
};

const stringPattern = (delimiter: string, escapeString: boolean): Pattern => ({
  name: 'string.quoted.double.design',
  begin: delimiter,
  end: delimiter,
  beginCaptures: {
    '0': {
      name: 'punctuation.definition.string.begin.design',
    },
  },
  endCaptures: {
    '0': {
      name: 'punctuation.definition.string.end.design',
    },
  },
  patterns: escapeString ? [{ include: '#escape' }] : [],
});

const interpolatedStringPattern: Pattern = {
  name: 'string.interpolated.design',
  begin: String.raw`\bs"`,
  end: String.raw`"`,
  beginCaptures: {
    '0': {
      name: 'punctuation.definition.string.begin.design',
    },
  },
  endCaptures: {
    '0': {
      name: 'punctuation.definition.string.end.design',
    },
  },
  patterns: [{ include: '#escape' }],
};

const annotation: Pattern = {
  name: 'entity.name.function.decorator.design',
  match: String.raw`(?<=@)${IDENTIFIER}`,
};

const typeIdentifier: Pattern = {
  name: 'entity.name.type.design',
  match: String.raw`\b(?:${TYPE_NAME}|String|Boolean|Double|Float|Int|Long|Unit)\b`,
};

const enumCaseIdentifier: Pattern = {
  name: 'entity.name.type.enum-member.design',
  match: String.raw`(?<=\bcase\s+)${IDENTIFIER}`,
};

const functionIdentifier: Pattern = {
  name: 'entity.name.function.design',
  match: String.raw`(?<=\bdef\s+)${IDENTIFIER}`,
};

const callIdentifier: Pattern = {
  name: 'entity.name.function.call.design',
  match: String.raw`\b${IDENTIFIER}(?=\s*\()`,
};

const identifier: Pattern = {
  name: 'variable.other.readwrite.design',
  match: String.raw`\b${IDENTIFIER}\b`,
};

const keywords: Pattern = {
  name: 'keyword.control.design',
  match: String.raw`\b(?:enum|class|def|case|match|if|else|for|while|throw|return|val|var|type|import|from|as|and|or|not|in|this)\b`,
};

const constants: Pattern = {
  name: 'constant.language.design',
  match: String.raw`\b(?:true|false|null)\b|\?\?\?`,
};

const numeric: Pattern = {
  name: 'constant.numeric.design',
  match: String.raw`\b(?:0x[\da-fA-F]+|0o[0-7]+|0b[01]+|\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\b`,
};

const operators: Pattern = {
  name: 'keyword.operator.design',
  match: String.raw`=>|->|==|!=|<=|>=|&&|\|\||[+\-*\/%<>:=|&!]`,
};

const punctuation: Pattern = {
  name: 'punctuation.separator.design',
  match: String.raw`[;,]`,
};

const designGrammar: Grammar = {
  $schema: 'https://raw.githubusercontent.com/martinring/tmlanguage/master/tmlanguage.json',
  name: 'Design',
  scopeName: 'source.design',
  patterns: [
    { include: '#comments' },
    { include: '#interpolatedStringPattern' },
    { include: '#tripleStringPattern' },
    { include: '#stringPattern' },
    { include: '#annotation' },
    { include: '#constants' },
    { include: '#numeric' },
    { include: '#keywords' },
    { include: '#functionIdentifier' },
    { include: '#enumCaseIdentifier' },
    { include: '#callIdentifier' },
    { include: '#typeIdentifier' },
    { include: '#operators' },
    { include: '#punctuation' },
    { include: '#identifier' },
  ],
  repository: {
    comments,
    blockComment,
    lineComment,
    escape,
    stringPattern: stringPattern(String.raw`"`, true),
    tripleStringPattern: stringPattern(String.raw`"""`, false),
    interpolatedStringPattern,
    annotation,
    constants,
    numeric,
    keywords,
    functionIdentifier,
    enumCaseIdentifier,
    callIdentifier,
    typeIdentifier,
    operators,
    punctuation,
    identifier,
  },
};

const dirname = fileURLToPath(new URL('.', import.meta.url));
const outPath = path.resolve(dirname, '../design.tmLanguage.json');

fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, `${JSON.stringify(designGrammar, null, 2)}\n`);

console.log(`Generated ${path.relative(process.cwd(), outPath)}`);
