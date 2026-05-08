import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  createConnection,
  ProposedFeatures,
  type Definition,
  type Diagnostic,
  type DidChangeTextDocumentParams,
  type DidCloseTextDocumentParams,
  type DidOpenTextDocumentParams,
  type ExecuteCommandParams,
  type Hover,
  type InitializeParams,
  type InitializeResult,
  type Location,
  type ReferenceParams,
  type TextDocumentPositionParams,
} from 'vscode-languageserver/node.js';

const COMPILE_TO_CXX_COMMAND = 'design.ls.compileToCxx';
const DEFAULT_CXX_OUTPUT_DIR = 'include';

type CoreCompileResult = {
  ok: boolean;
  content?: string;
  error?: string;
};

type CompileCxxRequest = {
  uri: string;
  text?: string;
  outputDir?: string;
};

type CompileCxxResult = {
  outputPath: string;
};

type DesignConfig = {
  cxxOutputDir?: string;
};

type DesignLanguageServerCore = {
  initialize(): InitializeResult;
  shutdown(): void;
  didOpen(uri: string, text: string): Diagnostic[];
  didChange(uri: string, text: string): Diagnostic[];
  didClose(uri: string): Diagnostic[];
  compileCxx(text: string): CoreCompileResult;
  hover(uri: string, line: number, character: number): Hover | null;
  definition(uri: string, line: number, character: number): Definition | null;
  references(
    uri: string,
    line: number,
    character: number,
    includeDeclaration: boolean,
  ): Location[] | null;
};

type DesignLanguageServerModule = {
  DesignLanguageServer: new () => DesignLanguageServerCore;
};

main().catch((error: unknown) => {
  console.error(error);
  process.exit(1);
});

async function main(): Promise<void> {
  const connection = createConnection(ProposedFeatures.all);
  const { DesignLanguageServer } = (await import(
    resolveDesignLanguageServerModuleUrl()
  )) as unknown as DesignLanguageServerModule;
  const server = new DesignLanguageServer();
  let workspaceRoots: string[] = [];

  connection.onInitialize((params: InitializeParams) => {
    workspaceRoots = workspaceRootPaths(params);
    return server.initialize();
  });
  connection.onShutdown(() => {
    server.shutdown();
  });

  connection.onDidOpenTextDocument((params: DidOpenTextDocumentParams) => {
    publishDiagnostics(
      connection,
      params.textDocument.uri,
      server.didOpen(params.textDocument.uri, params.textDocument.text),
    );
  });

  connection.onDidChangeTextDocument((params: DidChangeTextDocumentParams) => {
    const lastChange = params.contentChanges.at(-1);
    if (lastChange === undefined) {
      return;
    }

    publishDiagnostics(
      connection,
      params.textDocument.uri,
      server.didChange(params.textDocument.uri, lastChange.text),
    );
  });

  connection.onDidCloseTextDocument((params: DidCloseTextDocumentParams) => {
    publishDiagnostics(
      connection,
      params.textDocument.uri,
      server.didClose(params.textDocument.uri),
    );
  });

  connection.onHover((params: TextDocumentPositionParams) =>
    server.hover(params.textDocument.uri, params.position.line, params.position.character),
  );

  connection.onDefinition((params: TextDocumentPositionParams) =>
    server.definition(params.textDocument.uri, params.position.line, params.position.character),
  );

  connection.onReferences((params: ReferenceParams) =>
    server.references(
      params.textDocument.uri,
      params.position.line,
      params.position.character,
      params.context.includeDeclaration,
    ),
  );

  connection.onExecuteCommand((params: ExecuteCommandParams) =>
    executeCommand(server, workspaceRoots, params),
  );

  connection.listen();
}

async function executeCommand(
  server: DesignLanguageServerCore,
  workspaceRoots: string[],
  params: ExecuteCommandParams,
): Promise<CompileCxxResult> {
  if (params.command !== COMPILE_TO_CXX_COMMAND) {
    throw new Error(`Unsupported Design command: ${params.command}`);
  }

  const request = compileCxxRequest(params);
  const sourcePath = filePathFromUri(request.uri);
  const projectRoot = projectRootForFile(sourcePath, workspaceRoots);
  const config = await readDesignConfig(projectRoot);
  const outputDirectory = resolveOutputDirectory(
    projectRoot,
    request.outputDir ?? config.cxxOutputDir,
  );
  const outputPath = cxxOutputPath(sourcePath, outputDirectory);
  const source = request.text ?? (await fs.readFile(sourcePath, 'utf8'));
  const emitted = server.compileCxx(source);

  if (!emitted.ok) {
    throw new Error(emitted.error ?? `Failed to compile ${sourcePath}`);
  }
  if (typeof emitted.content !== 'string') {
    throw new Error('Design compiler returned no C++ output.');
  }

  await fs.mkdir(path.dirname(outputPath), { recursive: true });
  await fs.writeFile(outputPath, emitted.content, 'utf8');

  return { outputPath };
}

function compileCxxRequest(params: ExecuteCommandParams): CompileCxxRequest {
  const firstArgument = params.arguments?.[0] as unknown;
  if (typeof firstArgument === 'string') {
    return { uri: firstArgument };
  }

  const argument = recordValue(firstArgument);
  if (argument === undefined) {
    throw new Error('Design compile command requires a file URI.');
  }

  const uri = argument.uri;
  if (typeof uri !== 'string' || uri.length === 0) {
    throw new Error('Design compile command requires a file URI.');
  }

  const text = typeof argument.text === 'string' ? argument.text : undefined;
  const outputDir = typeof argument.outputDir === 'string' ? argument.outputDir : undefined;

  return {
    uri,
    ...(text === undefined ? {} : { text }),
    ...(outputDir === undefined ? {} : { outputDir }),
  };
}

function workspaceRootPaths(params: InitializeParams): string[] {
  const roots = params.workspaceFolders
    ?.map((folder) => pathFromFileUri(folder.uri))
    .filter((folder): folder is string => folder !== undefined);

  if (roots !== undefined && roots.length > 0) {
    return roots.map((root) => path.resolve(root));
  }

  const rootPath =
    params.rootUri === null || params.rootUri === undefined
      ? undefined
      : pathFromFileUri(params.rootUri);
  return rootPath === undefined ? [] : [path.resolve(rootPath)];
}

function projectRootForFile(filePath: string, workspaceRoots: string[]): string {
  const normalizedFilePath = path.resolve(filePath);
  const matchingRoot = workspaceRoots
    .map((root) => path.resolve(root))
    .filter((root) => isPathInside(normalizedFilePath, root))
    .sort((left, right) => right.length - left.length)
    .at(0);

  return matchingRoot ?? path.dirname(normalizedFilePath);
}

async function readDesignConfig(projectRoot: string): Promise<DesignConfig> {
  const configPath = path.join(projectRoot, 'design.toml');

  try {
    const content = await fs.readFile(configPath, 'utf8');
    return parseDesignToml(content, configPath);
  } catch (error: unknown) {
    if (isNodeError(error) && error.code === 'ENOENT') {
      return {};
    }
    throw error;
  }
}

function parseDesignToml(content: string, configPath: string): DesignConfig {
  let section = '';
  let cxxOutputDir: string | undefined;
  const lines = content.split(/\r?\n/u);

  lines.forEach((rawLine, index) => {
    const lineNumber = index + 1;
    const line = stripTomlComment(rawLine).trim();
    if (line.length === 0) {
      return;
    }

    const tableMatch = /^\[([A-Za-z0-9_.-]+)\]$/u.exec(line);
    if (tableMatch !== null) {
      section = tableMatch[1] ?? '';
      return;
    }

    const keyValueMatch = /^([A-Za-z0-9_.-]+)\s*=\s*(.+)$/u.exec(line);
    if (keyValueMatch === null) {
      throw new Error(`${configPath}:${lineNumber}: expected a TOML key/value pair.`);
    }

    const rawKey = keyValueMatch[1] ?? '';
    const rawValue = keyValueMatch[2] ?? '';
    const keyParts = rawKey.split('.');
    const key = keyParts.at(-1) ?? '';
    const keySection = [
      ...(section.length === 0 ? [] : section.split('.')),
      ...keyParts.slice(0, -1),
    ].join('.');

    if (keySection === 'cxx' && isCxxOutputDirectoryKey(key)) {
      cxxOutputDir = parseTomlString(rawValue, configPath, lineNumber);
    }
  });

  return cxxOutputDir === undefined ? {} : { cxxOutputDir };
}

function stripTomlComment(line: string): string {
  let inBasicString = false;
  let inLiteralString = false;
  let escaping = false;

  for (let index = 0; index < line.length; index += 1) {
    const char = line[index];

    if (inBasicString) {
      if (escaping) {
        escaping = false;
      } else if (char === '\\') {
        escaping = true;
      } else if (char === '"') {
        inBasicString = false;
      }
      continue;
    }

    if (inLiteralString) {
      if (char === "'") {
        inLiteralString = false;
      }
      continue;
    }

    if (char === '#') {
      return line.slice(0, index);
    }
    if (char === '"') {
      inBasicString = true;
    } else if (char === "'") {
      inLiteralString = true;
    }
  }

  return line;
}

function parseTomlString(rawValue: string, configPath: string, lineNumber: number): string {
  const value = rawValue.trim();

  if (value.startsWith('"')) {
    if (!value.endsWith('"')) {
      throw new Error(`${configPath}:${lineNumber}: expected a complete quoted string.`);
    }
    const parsed = JSON.parse(value) as unknown;
    if (typeof parsed === 'string') {
      return parsed;
    }
  } else if (value.startsWith("'")) {
    if (value.length >= 2 && value.endsWith("'")) {
      return value.slice(1, -1);
    }
  } else if (/^[^\s]+$/u.test(value)) {
    return value;
  }

  throw new Error(`${configPath}:${lineNumber}: expected a string value.`);
}

function isCxxOutputDirectoryKey(key: string): boolean {
  return key === 'output_dir' || key === 'outputDir' || key === 'out_dir' || key === 'outDir';
}

function resolveOutputDirectory(projectRoot: string, outputDir: string | undefined): string {
  const rawOutputDir = outputDir ?? DEFAULT_CXX_OUTPUT_DIR;
  if (rawOutputDir.trim().length === 0) {
    throw new Error('Design C++ output directory cannot be empty.');
  }

  return path.isAbsolute(rawOutputDir)
    ? path.normalize(rawOutputDir)
    : path.resolve(projectRoot, rawOutputDir);
}

function cxxOutputPath(sourcePath: string, outputDirectory: string): string {
  const extension = path.extname(sourcePath);
  const baseName = path.basename(sourcePath, extension.length > 0 ? extension : undefined);
  return path.join(outputDirectory, `${baseName}.h`);
}

function filePathFromUri(uri: string): string {
  const filePath = pathFromFileUri(uri);
  if (filePath === undefined) {
    throw new Error(`Design compile command only supports file URIs: ${uri}`);
  }
  return filePath;
}

function pathFromFileUri(uri: string): string | undefined {
  if (!uri.startsWith('file:')) {
    return undefined;
  }
  return fileURLToPath(uri);
}

function isPathInside(filePath: string, root: string): boolean {
  const relative = path.relative(root, filePath);
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
}

function recordValue(value: unknown): Record<string, unknown> | undefined {
  if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  return undefined;
}

function isNodeError(error: unknown): error is NodeJS.ErrnoException {
  return error instanceof Error && 'code' in error;
}

function publishDiagnostics(
  connection: ReturnType<typeof createConnection>,
  uri: string,
  diagnostics: Diagnostic[],
): void {
  connection.sendDiagnostics({ uri, diagnostics });
}

function resolveDesignLanguageServerModuleUrl(): string {
  const modulePath = process.env.DESIGN_LS_MODULE_PATH;
  if (modulePath !== undefined && modulePath.length > 0) {
    return modulePath.startsWith('file:')
      ? modulePath
      : pathToFileURL(path.resolve(modulePath)).href;
  }

  const entrypoint = process.argv[1] ?? process.cwd();
  return pathToFileURL(path.join(path.dirname(entrypoint), 'scala', 'design-ls', 'main.js')).href;
}
