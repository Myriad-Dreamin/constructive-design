# constructive-design

[English](README.md) | 中文

Constructive Design 定义了一种轻量级的 Design Syntax，用于与 agent 讨论、
描述、生成和检查结构化架构。

Design Syntax 是 Scala 的一个子集，语法表面刻意保持很小：`def`、`val`、
`var`、`class`、`match` 和表达式。它只保留适合与 agent 讨论、解析、分析，
并降低到 C++、TypeScript 等目标语言的结构。

## Design Syntax

将 `.ds` 文件作为 agent 可读的架构描述层使用：

- 使用接近可执行代码的语法与 agent 讨论结构化设计。
- 用紧凑的类型化形式描述领域模型、状态机、规则和转换。
- 生成目标语言产物，例如 C++ 头文件。
- 在依赖生成结果前，检查设计是否可以安全地翻译到目标语言。
- 当你不想深入实现细节时，使用 `???`。这个特殊 literal 会把具体实现决策
  留给 agent。

## Scripts

- `pnpm build`：构建 VSCode 扩展包，然后将 TypeScript 编译到 `dist`。
- `pnpm build:cli`：构建 Scala.js 编译器模块，然后将 Node CLI 编译到 `dist`。
- `pnpm build:sea`：在 `dist/dsc` 生成 Node 单文件可执行文件。
- `pnpm build:vscode`：从 `editors/vscode` 构建并打包 VSCode 扩展。

## CLI

Node CLI 名为 `dsc`：

```shell
dsc help
dsc version
dsc --help
dsc --version
dsc compile .
dsc compile . -o include
```

`dsc compile <path>` 支持传入单个 `.ds` 文件或目录。目录输入会递归扫描
`.ds` 文件，并跳过 `dist`、`target`、`.git`、`node_modules` 等构建和依赖目录。

生成的 C++ 头文件默认写入 `include/`。可以用 `-o`/`--output` 指定输出目录，
也可以在 `design.toml` 配置默认输出目录：

```toml
[cxx]
output_dir = "generated/include"
```

相对 `-o` 路径会按当前工作目录解析。相对 `design.toml` 路径会按被编译的目录
输入解析；编译单个文件时则按当前工作目录解析。

生成独立可执行文件：

```shell
pnpm build:sea
./dist/dsc version
```

SEA 构建使用 Node 的 `--build-sea` 流程，需要 Node.js 25.5.0 或更新版本。
生成的可执行文件会内嵌 CLI 入口和 Scala.js Design 编译器模块，运行时不需要
本地 `node_modules` 目录。

## C++ 编译命令

VSCode 扩展提供 `Design: Compile to C++` 命令。在已保存的 `.ds` 文件中运行后，
会生成对应的 C++ 头文件。默认输出到工作区根目录的 `include/` 文件夹，文件名为
`<源文件名>.h`。

可以在工作区根目录添加 `design.toml` 配置输出目录：

```toml
[cxx]
output_dir = "generated/include"
```

相对路径会按工作区根目录解析，绝对路径会直接使用。

## Scala Packages

- `packages/design`：共享的 Design 编译器、解析器和 emitter 代码。
- `packages/design-cli`：用于生成 C++ 头文件的 CLI 入口。
- `packages/design-ls`：VSCode 扩展使用的 Scala.js LSP server core。
