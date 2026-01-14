# Contributing to Bengal STM

Thank you for your interest in contributing to Bengal STM! This document provides guidelines and information for contributors.

## Code of Conduct

This project adheres to a [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

## How to Contribute

### Reporting Issues

Before creating an issue, please check if a similar issue already exists. When reporting bugs, include:

- A clear, descriptive title
- Steps to reproduce the issue
- Expected vs actual behavior
- Scala version, Java version, and Bengal STM version
- Minimal code example if applicable

### Suggesting Features

Feature suggestions are welcome! Please provide:

- A clear description of the feature
- The problem it solves or use case it enables
- Any implementation ideas you have

### Pull Requests

1. **Fork the repository** and create your branch from `main`
2. **Write tests** for any new functionality
3. **Ensure all tests pass** by running `sbt test`
4. **Format your code** by running `sbt scalafmtAll`
5. **Update documentation** if you're changing public APIs
6. **Submit a pull request** with a clear description of your changes

## Development Setup

### Requirements

- Java 21 or later
- sbt

### Building

```bash
# Compile the project
sbt compile

# Run tests
sbt test

# Format code
sbt scalafmtAll

# Check formatting
sbt scalafmtCheckAll
```

## Code Style

This project uses [Scalafmt](https://scalameta.org/scalafmt/) for code formatting. Configuration is in `.scalafmt.conf`.

Key conventions:
- Follow existing code patterns and style
- Use meaningful names for variables, methods, and classes
- Add comments for complex logic
- Prefer immutability and functional patterns

## Testing

- All new features should include tests
- Bug fixes should include a test that would have caught the bug
- Tests are located in `src/test/scala`
- Run tests with `sbt test`

## License

By contributing to Bengal STM, you agree that your contributions will be licensed under the Apache License 2.0.

## Questions?

If you have questions about contributing, feel free to open an issue for discussion.
