import * as path from 'node:path';
import * as vscode from 'vscode';
import {
  ExecuteCommandRequest,
  LanguageClient,
  type LanguageClientOptions,
  type ServerOptions,
  TransportKind,
} from 'vscode-languageclient/node.js';

const COMPILE_TO_CXX_COMMAND = 'design.compileToCxx';
const LSP_COMPILE_TO_CXX_COMMAND = 'design.ls.compileToCxx';

type CompileCxxResult = {
  outputPath: string;
};

let client: LanguageClient | undefined;

export function activate(context: vscode.ExtensionContext): void {
  const serverModule = context.asAbsolutePath(path.join('dist', 'server.cjs'));
  const serverOptions: ServerOptions = {
    run: {
      module: serverModule,
      transport: TransportKind.ipc,
    },
    debug: {
      module: serverModule,
      transport: TransportKind.ipc,
      options: {
        execArgv: ['--nolazy', '--inspect=6009'],
      },
    },
  };

  const clientOptions: LanguageClientOptions = {
    documentSelector: [
      { language: 'design', scheme: 'file' },
      { language: 'design', scheme: 'untitled' },
    ],
    synchronize: {
      fileEvents: [
        vscode.workspace.createFileSystemWatcher('**/*.ds'),
        vscode.workspace.createFileSystemWatcher('**/design.toml'),
      ],
    },
  };

  client = new LanguageClient(
    'designLanguageServer',
    'Design Language Server',
    serverOptions,
    clientOptions,
  );

  const startPromise = client.start();
  void startPromise;

  context.subscriptions.push(
    vscode.commands.registerCommand(COMPILE_TO_CXX_COMMAND, async () => {
      try {
        await compileActiveDocument(startPromise);
      } catch (error: unknown) {
        vscode.window.showErrorMessage(errorMessage(error));
      }
    }),
    {
      dispose: () => {
        void client?.stop();
      },
    },
  );
}

export function deactivate(): Thenable<void> | undefined {
  return client?.stop();
}

async function compileActiveDocument(startPromise: Thenable<void>): Promise<void> {
  const document = vscode.window.activeTextEditor?.document;
  if (document === undefined || document.languageId !== 'design') {
    vscode.window.showWarningMessage('Open a Design file before compiling.');
    return;
  }
  if (document.uri.scheme !== 'file') {
    vscode.window.showErrorMessage('Design compile only supports files saved on disk.');
    return;
  }

  const currentClient = client;
  if (currentClient === undefined) {
    throw new Error('Design language client is not running.');
  }

  await startPromise;

  const result = (await currentClient.sendRequest(ExecuteCommandRequest.type, {
    command: LSP_COMPILE_TO_CXX_COMMAND,
    arguments: [
      {
        uri: document.uri.toString(),
        text: document.getText(),
      },
    ],
  })) as CompileCxxResult;
  const outputPath = vscode.workspace.asRelativePath(result.outputPath, false);

  vscode.window.showInformationMessage(`Generated ${outputPath}`);
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
