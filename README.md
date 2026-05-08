# constructive-design

TypeScript project initialized with pnpm, ESLint, and Prettier.

## Scripts

- `pnpm build`: compile TypeScript to `dist`
- `pnpm typecheck`: run TypeScript checks without emitting files
- `pnpm lint`: run ESLint
- `pnpm format`: format files with Prettier
- `pnpm format:check`: check Prettier formatting
- `pnpm check`: run lint, format check, and typecheck
- `pnpm start`: run the compiled entry after `pnpm build`
- `pnpm scala:run`: parse `syntax/design.des` with Scala.js/FastParse on Node.js
- `pnpm scala:sbt-run`: run the Scala.js entry with Node.js through sbt
- `pnpm scala:sbt-fastLink`: build the fast Scala.js output with sbt
