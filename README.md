# constructive-design

English | [中文](README.zh.md)

Constructive Design defines a lightweight Design Syntax for discussing, describing,
generating, and checking structured architecture with agents.

Design Syntax is a subset of Scala with a deliberately small surface area: `def`,
`val`, `var`, `class`, `match`, and expressions. The syntax is limited to constructs
that can be discussed with agents, parsed, analyzed, and lowered into target languages
such as C++ and TypeScript.

## Design Syntax

Use `.ds` files as an agent-readable architecture description layer:

- Discuss structured designs with agents using syntax that is close to executable code.
- Describe domain models, state machines, rules, and transitions in a compact typed form.
- Generate target-language artifacts, such as C++ headers.
- Check whether a design can be safely translated into a target language before relying
  on generated output.
- Use `???` when you do not want to dive into implementation details. This special
  literal leaves the concrete implementation decision to the agent.

## Scripts

- `pnpm build`: build the VSCode extension package, then compile TypeScript to `dist`
- `pnpm build:cli`: build the Scala.js compiler module, then compile the Node CLI to `dist`
- `pnpm build:sea`: build a Node single executable application at `dist/dsc`
- `pnpm build:vscode`: build and package the VSCode extension from `editors/vscode`

## CLI

The Node CLI is named `dsc`:

```shell
dsc help
dsc version
dsc --help
dsc --version
dsc compile .
dsc compile . -o include
```

`dsc compile <path>` accepts either a single `.ds` file or a directory. A directory
input is scanned recursively for `.ds` files, skipping build and dependency
directories such as `dist`, `target`, `.git`, and `node_modules`.

Generated C++ headers are written to `include/` by default. Use `-o`/`--output` to
choose another output directory, or configure the default in `design.toml`:

```toml
[cxx]
output_dir = "generated/include"
```

Relative `-o` paths are resolved from the current working directory. Relative
`design.toml` paths are resolved from the compiled directory input, or from the
current working directory when compiling a single file.

To generate the standalone executable:

```shell
pnpm build:sea
./dist/dsc version
```

The SEA build uses Node's `--build-sea` flow and requires Node.js 25.5.0 or newer.
The executable embeds the CLI entry point and the Scala.js Design compiler module,
so it can run without a local `node_modules` directory.

## C++ Compile Command

The VSCode extension contributes `Design: Compile to C++`. Run it from a saved `.ds`
file to generate a C++ header. The command writes `<source-name>.h` into the
workspace root `include/` directory by default.

Use `design.toml` at the workspace root to choose another output directory:

```toml
[cxx]
output_dir = "generated/include"
```

Relative paths are resolved from the workspace root. Absolute paths are used as-is.

## Scala Packages

- `packages/design`: shared Design compiler/parser/emitter code.
- `packages/design-cli`: CLI entry point for generating C++ headers.
- `packages/design-ls`: Scala.js LSP server core used by the VSCode extension.
