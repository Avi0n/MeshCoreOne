**Thank you for your interest in contributing to MeshCore One!**

To make the contribution process smooth and respectful of everyone's time, please follow these guidelines:

### Before Starting Work
- **Discuss your idea first**: Reach out so we can coordinate. This helps avoid duplicate effort, as I might already be working on something similar.
- [MeshCore Discord](https://meshcore.gg) — look for the MeshCore One forum post.
- Or message me on Matrix @avion:matrix.org

### Pull Request Requirements
When submitting a PR, please:
- Include a clear description of the feature or fix.
- Include an overview of the changes made.
- Run `swiftlint lint` and fix any warnings or errors.
- Run the test suite and confirm all tests pass.
- Describe the testing steps you performed.

### Important Note for AI-Assisted Contributions
If you're an experienced software engineer and did not rely heavily on AI for your contribution, skip to Getting Started.

If you used AI extensively (which is totally fine, I built this entire project with AI despite not being a SWE and only having basic Python scripting experience), please follow these best practices to ensure high-quality results:

0. **If you have no previous coding experience**
It's usually easier to simply create a feature request GitHub issue with your idea and then I can implement it using my established workflows. Feel free to create mock ups with AI and attach those screenshots in your feature request!

1. **Choose the right model for planning**  
   If you're not a software engineer and aren't comfortable creating detailed technical plans yourself, stick to the strongest reasoning models: **Claude Opus** or **GPT Codex (high/xhigh)**. These are decent at turning non-technical ideas into solid implementation plans. Other popular models (e.g., GLM, MiniMax) perform well when given a detailed plan, but struggle to create one from scratch in a large codebase.

2. **Plan thoroughly**  
   Ask the AI to use research agents/tools to gather context about the relevant parts of the codebase. Think of edge cases. Write the plan to an md file.

3. **Review the plan**  
   Start a fresh chat and ask the AI to critically review the plan.

4. **Refine the plan**  
   Have the AI validate the review, then adjust the plan as necessary.

5. **Implement**  
   Have the AI follow the finalized plan to make the changes.

6. **Validate the implementation**  
   In a new chat, ask the AI to review the code changes. Claude Code and Codex both have a built-in `review` tool for this.

7. **Test thoroughly**  
   Manually verify that everything works as expected. Test edge cases.

8. **Submit the PR**  
   In the description, include the reason you are making the changes. You can ask the AI to draft the PR description. Feel free to use it directly, but adding a bit of your own voice is always appreciated!


## Getting Started

### Prerequisites

- **Xcode 26.0+**
- **Swift 6.2+**
- **XcodeGen**: Required for project file generation.
- **SwiftGen**: Required for localization.
  ```bash
  brew install xcodegen swiftgen
  ```
- **xcsift** (optional): Transforms verbose Xcode output into concise JSON.
  ```bash
  brew install xcsift
  ```

### Project Setup

1. **Clone the repository**.
2. **Generate the Xcode project**:
   ```bash
   xcodegen generate
   ```
3. **Open `MC1.xcodeproj`**.

### Branch Workflow

- All development happens on the `dev` branch. `main` is reserved for releases.
- Fork the repo and create your branch from `dev`.
- Use the naming convention `{type}/short-descriptive-name`, where type is one of:
  - `feature` — new functionality
  - `fix` — bug fix
  - `refactor` — code restructuring without behavior change
  - `chore` — tooling, CI, docs, or other non-code changes
- Target your PR to `dev`, not `main`.


## Thank You!
Thank you again for your interest in contributing. I'm excited to see what you build!
