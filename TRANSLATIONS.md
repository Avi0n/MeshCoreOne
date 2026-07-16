# Translations

MeshCore One supports multiple languages. You can help improve translations entirely through GitHub's website — no coding tools or experience required.

## Supported Languages

| Language              | Code    | Status             |
|-----------------------|---------|--------------------|
| English               | en      | Complete (source)  |
| German                | de      | AI-translated      |
| Dutch                 | nl      | AI-translated      |
| Polish                | pl      | AI-translated      |
| Russian               | ru      | AI-translated      |
| Ukrainian             | uk      | AI-translated      |
| Spanish               | es      | AI-translated      |
| French                | fr      | AI-translated      |
| Italian               | it      | AI-translated      |
| Simplified Chinese    | zh-Hans | Verified by MGJ520 |

Languages marked "AI-translated" were generated using AI and may contain errors. Native speaker verification is welcome.

## Quick Reference

Translation files use a simple format:

```
/* Description of what this text is for */
"settings.title" = "Settings";
```

- **Left side** (`"settings.title"`) — the key. Don't change this.
- **Right side** (`"Settings"`) — the translation. This is what you edit.
- **`%@`** — a placeholder for text (e.g., a name). Keep it in your translation.
- **`%d`** — a placeholder for a number. Keep it in your translation.
- **Comments** (`/* ... */`) — context to help you translate. Don't edit these.

## How to Improve Existing Translations

You can fix a translation directly on GitHub — no tools needed.

1. Go to the [Localization folder](https://github.com/Avi0n/MeshCoreOne/tree/dev/MC1/Resources/Localization) and open your language's folder (e.g., `de.lproj` for German).
2. Click the `.strings` file you want to edit (e.g., `Settings.strings`).
3. Click the **pencil icon** (Edit this file) near the top-right of the file.
4. GitHub will ask you to **fork the repository** — click **"Fork this repository"**. This creates your own copy so you can make changes.
5. The editor opens. Find the line you want to fix and change the text on the **right side** of the `=` sign.
6. Click **"Commit changes…"**, then click **"Propose changes"**.

**If you want to edit more files** before submitting, don't create the pull request yet. Instead, go to your fork (GitHub shows a link to it at the top of the page). Find the branch it created (e.g., `patch-1`), navigate to the next file you want to edit, and repeat steps 5–6. Each edit adds another commit to the same branch.

**When you're done with all your edits**, go to the "Comparing changes" page (GitHub shows this after each commit, or you can find it on your fork under "Contribute" → "Open pull request"). Use the **base** dropdown to select **`dev`** as the target branch, then click **Create pull request**.

**Example:** Fixing a German translation in `de.lproj/Settings.strings`:

```
/* Before */
"settings.title" = "Einstellungen";

/* After — your improved translation */
"settings.title" = "Konfiguration";
```

## How to Add Missing Translations

If you see English text while using MeshCore One in another language, a translation is missing. Here's how to add it:

1. First, find the English text. Go to `MC1/Resources/Localization/en.lproj/` and search through the `.strings` files until you find the line with that English text.
2. Note the **key** (the left side of the `=` sign) and which **file** it's in.
3. Go to the same file in your language's folder (e.g., `de.lproj/`).
4. Click the **pencil icon** to edit, then click **"Fork this repository"** when prompted.
5. Add a new line with the key and your translation:
   ```
   "the.key.you.found" = "Your translation here";
   ```
6. Click **"Commit changes…"**, then click **"Propose changes"**.
7. To add more missing translations, navigate to the next file on your fork's branch and repeat steps 5–6.
8. When you're done, go to the "Comparing changes" page, use the **base** dropdown to select **`dev`**, then click **Create pull request**.

## How to Request a New Language

Open an [issue](https://github.com/Avi0n/MeshCoreOne/issues) and include:

- The language name and code (e.g., "Japanese — ja")
- Whether you'd be willing to help review translations

We can generate AI translations for new languages, but native speaker review makes a big difference.

## File Structure

Translation files are organized by feature under `MC1/Resources/Localization/`:

```
Localization/
  en.lproj/           # English (source language)
    Localizable.strings       # Common strings (buttons, tabs, errors)
    Localizable.stringsdict   # Pluralization rules for common strings
    Chats.strings             # Chat feature
    Chats.stringsdict         # Pluralization for chat feature
    Contacts.strings          # Contacts and nodes
    Map.strings               # Map feature
    Onboarding.strings        # Onboarding flow
    Settings.strings          # Settings screens
    Tools.strings             # Tools feature
    RemoteNodes.strings       # Remote nodes feature
  de.lproj/           # German
  nl.lproj/           # Dutch
  ...                 # Other languages
```

---

## For Developers

The sections below are for code contributors adding new strings or working with pluralization. If you're only translating, you can stop here.

### Adding New Strings

#### Step 1: Add to English .strings File

Add your string to the appropriate feature file in `en.lproj/`:

```
/* Location: MyView.swift - Purpose: Button to submit form */
"myFeature.submitButton" = "Submit";
```

Include a comment with file location and purpose.

#### Step 2: Use the Generated Constant

SwiftGen generates type-safe constants. After building, use:

```swift
// For Localizable.strings
L10n.Localizable.Common.ok

// For feature-specific files
L10n.Chats.Conversation.sendButton
L10n.Settings.Account.title
```

#### Step 3: Add Translations

Add the same key to all other language files. You can use AI translation as a starting point, but mark it for review:

```
/* Location: MyView.swift - Purpose: Button to submit form */
/* AI-translated - please verify with native speakers. */
"myFeature.submitButton" = "Absenden";
```

### Pluralization

Use `.stringsdict` files for strings that change based on quantity.

#### Simple Languages (English, German, Dutch, Spanish, French)

These languages use two forms: `one` (exactly 1) and `other` (0, 2+).

```xml
<key>items.count</key>
<dict>
    <key>NSStringLocalizedFormatKey</key>
    <string>%#@count@</string>
    <key>count</key>
    <dict>
        <key>NSStringFormatSpecTypeKey</key>
        <string>NSStringPluralRuleType</string>
        <key>NSStringFormatValueTypeKey</key>
        <string>d</string>
        <key>one</key>
        <string>%d item</string>
        <key>other</key>
        <string>%d items</string>
    </dict>
</dict>
```

#### Slavic Languages (Polish, Russian, Ukrainian)

These languages have complex plural rules with four forms:

| Form  | Polish Example    | Numbers             |
|-------|-------------------|---------------------|
| one   | 1 wiadomosc       | 1                   |
| few   | 2-4 wiadomosci    | 2-4, 22-24, 32-34… |
| many  | 5-21 wiadomosci   | 0, 5-21, 25-31…    |
| other | 1.5 wiadomosci    | Fractions           |

Example for Russian:
```xml
<key>one</key>
<string>%d сообщение</string>
<key>few</key>
<string>%d сообщения</string>
<key>many</key>
<string>%d сообщений</string>
<key>other</key>
<string>%d сообщений</string>
```

### Testing Translations

1. Build the app
2. Change the simulator/device language in Settings
3. Launch MeshCore One and verify strings appear correctly

For German translations, test at the largest Dynamic Type size since German words are often longer than English.

## Questions

Open an issue for translation questions or to report incorrect translations.
