# AiderDesk Connector IntelliJ Plugin

## Overview

AiderDesk Connector is an IntelliJ plugin that connects to the AiderDesk tool, tracking file operations in your project to enable seamless integration with Aider's AI-powered pair programming capabilities.

Learn more about AiderDesk: [https://github.com/hotovo/aider-desk](https://github.com/hotovo/aider-desk)

## Features

- WebSocket client connecting to AiderDesk on port 24337 (alphanumeric code for 'aider')
- Tracks currently opened files in IntelliJ IDEA
- Provides real-time file status updates to AiderDesk
- Seamless integration with Aider's AI pair programming workflow

## Usage

The plugin automatically starts when IntelliJ IDEA launches. No manual configuration is needed - just make sure AiderDesk is running.

The plugin will:
1. Connect to AiderDesk on port 24337
2. Track your project's open files
3. Send real-time updates as you work with files

## Installation

1. Open IntelliJ IDEA
2. Go to File > Settings > Plugins
3. Search for "AiderDesk Connector" in the Marketplace tab
4. Click Install and restart your IDE

## Development

To build and run the plugin:
1. Clone the repository
2. Import the project in IntelliJ IDEA
3. Configure the Gradle build
4. Run the `runIde` task to launch a development IDE instance

## Contributing

Contributions are welcome! Please submit pull requests or open issues on the GitHub repository.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
