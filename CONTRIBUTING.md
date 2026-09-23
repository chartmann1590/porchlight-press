# Contributing to Porchlight Press

Thanks for your interest! There are lots of ways to help, and most of them need no code at all.

## Ways to help

- **Suggest a news source** for your town (a local paper, TV station, city or county office, school district, or police/fire department with a public news feed). [Open a source suggestion](https://github.com/chartmann1590/porchlight-press/issues/new/choose).
- **Ask for your town** to get a local section.
- **Report a bug** or a story that looks wrong.
- **Suggest a feature.**
- **Contribute code or translations fixes.** See below.

## Before you open an issue

- Search existing issues first. Someone may have already reported it.
- **Security problems:** don't open a public issue. Follow the [Security Policy](SECURITY.md).
- Never post personal information (yours or anyone else's) in an issue.

## Contributing code

1. Open an issue first for anything bigger than a small fix, so we can agree on the approach.
2. Fork the repository and create a branch from `main` (for example `feature/short-description` or `fix/short-description`).
3. Keep changes focused. One pull request per change.
4. Add or update tests for what you change, and make sure all tests pass.
5. Open a pull request using the template and describe what changed and how you tested it.

**Ground rules for code:**

- The project must stay **$0 to run**: no paid services and no API keys that could bill.
- Never commit secrets, signing keys, `google-services.json`, or AI model files.
- Respect publishers: no scraping of full articles and no reusing photos without permission. See the [Content & Attribution Policy](CONTENT_POLICY.md).
- Anything that sends data off the phone has to be explained in the pull request, and may require a [Privacy Policy](PRIVACY.md) update.

## License

By contributing, you agree that your contributions are licensed under the project's [Apache License 2.0](LICENSE).

## Code of Conduct

Everyone taking part is expected to follow our [Code of Conduct](CODE_OF_CONDUCT.md).
