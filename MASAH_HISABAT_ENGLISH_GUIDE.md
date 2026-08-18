# Masah Hisabat

> **A privacy-focused Arabic Android application for managing accounts, groups, invoices, messages, and images locally—without depending on the internet or Google services.**

## Product Overview

**Masah Hisabat** is a right-to-left Android application designed for individuals and small teams that need a practical way to organize accounts, groups, invoice records, messages, and image attachments. Its daily workflow is local-first: data remains on the device, is kept in a durable external folder, and can be exchanged between nearby devices over the same Wi-Fi network.

The interface uses a calm Teal visual identity, the Tajawal Arabic typeface, clear input contrast, and responsive layouts for both phones and tablets. The application also supports light, dark, and system-following appearance modes.

## Short Description

Masah Hisabat is an offline Arabic Android app for managing groups, invoices, messages, images, users, and permissions. It keeps data in persistent local storage, provides daily reports and search, and synchronizes groups, invoices, attachments, and authorized user accounts across devices on the same local Wi-Fi network. It also supports local app updates between nearby devices.

## Core Features

| Area | Included Functionality |
|---|---|
| **Sign in** | A full-screen Arabic sign-in experience with a clear “Remember me” option and local session persistence. |
| **Dashboard** | A concise activity overview, recent actions, quick synchronization access, and local data-status notifications. |
| **Groups** | Create, name, pin, sort, and safely remove groups, with an undo path for accidental deletion. |
| **Invoices and messages** | Send text, images, or both in one entry; edit and share entries; view author, date, time, and read status. |
| **Image viewer** | Open images full-screen, swipe between group images, and zoom with pinch gestures. |
| **Team management** | Create and edit users, passwords, status, and fine-grained permissions for authorized administrators. |
| **Search and reporting** | Local search with filters, daily reports, and activity statistics that can be reviewed or shared locally. |
| **Persistent storage** | Keeps groups, invoices, messages, user records, and attachments in `Documents/MasahHisabat`. |
| **Local synchronization** | Supports manual transfer with visible progress, plus automatic two-way group, invoice, and image synchronization after sign-in. |
| **Local updates** | Detects a newer app version on the same network, transfers its APK with integrity verification, and shows a completion notification. |

## How to Use the Application

### Sign In and Dashboard

Open the application and sign in with an authorized account. When **Remember me** is selected, a clear check mark appears in the box and the session is retained for future launches. After sign-in, the dashboard provides access to the main sections through a simplified bottom navigation bar: Home, Groups, Scanner, Search, Appearance, and Settings.

### Create and Manage Groups

Open **Groups** and choose the add action. Enter the group name in the naming field, then confirm it with the ✓ action. The naming dialog stays open when the user taps outside it, preventing accidental loss of typed text. A group name is managed from its dedicated **⋯** menu rather than by tapping the name itself.

### Add Invoices, Messages, and Images

Each group contains a bottom composer modeled after familiar messaging applications. Users can add text, an image, or a combined text-and-image entry. Entries use alternating bubble tones for visual separation and display the sender, date, time, and read status. An edit action and a separate share action are kept clear of the message bubble to reduce accidental taps.

Tap an attached image to open it in the full-screen viewer. Swipe left or right to move between images in the group, and use two fingers to zoom in and out.

### Manage Users and Permissions

The Team Management area is visible only to authorized administrators. It supports creating users and editing their names, passwords, active status, and permissions. Each account can be set to view, edit, delete, or read-only access as appropriate. User-name processing is normalized when accounts are created, checked, and synchronized, helping prevent sign-in issues caused by extra spaces or letter-case differences.

### Automatic Local Synchronization After Sign-In

Connect participating devices to the same local Wi-Fi network and open the application on each device. As soon as a user signs in, the application discovers nearby active devices and starts an automatic two-way exchange of **new groups, invoices, and attached images**. The process merges new records without deleting local data and creates a protective backup before applying incoming data.

Manual synchronization remains available when progress needs to be viewed directly. It provides a progress indicator and clear success or failure feedback. Automatic synchronization records its results in the synchronization log and uses limited rediscovery to handle devices that become available shortly afterward. Authorized user-account synchronization continues through its dedicated administration path.

### Local App Updates

When a device on the same local network has a newer compatible application version, Masah Hisabat can transfer the APK locally. The application verifies file integrity, displays a visible completion notification with the version and source device, and opens Android’s official installer only when the user taps the notification. The user remains in control of whether to install the update.

> **First-time setup note:** Install a version that includes the local-update feature manually on each device once. Subsequent compatible versions can then be transferred through the local network.

## Data Storage and Privacy

The application stores its data in `Documents/MasahHisabat`, with an internal fallback path if external storage is unavailable. This means groups, invoices, messages, images, and user data remain available after the application is removed and installed again, as long as the data folder itself has not been deleted manually.

Masah Hisabat does not need cloud services, Google Play services, or internet access for its core workflow. Search, reporting, and storage run locally. Wi-Fi is used only for local synchronization and local APK updates between devices on the same network.

## Operating Recommendations

| Recommendation | Purpose |
|---|---|
| Use a stable local Wi-Fi network for synchronization and updates. | Reduces interrupted transfers and connection timeouts. |
| Keep the `Documents/MasahHisabat` folder if data retention is required. | Preserves persistent records and attachments after reinstalling the app. |
| Assign each team member only the permissions they need. | Helps prevent accidental edits or deletions. |
| Review activity and synchronization logs periodically. | Makes network or data-transfer issues easier to identify. |
| Use system-following appearance mode when preferred. | Keeps the app aligned with the phone or tablet’s light/dark setting. |

## Technical Summary

| Component | Details |
|---|---|
| Language | Kotlin |
| Minimum Android version | Android 8.0 (API 26) |
| Target SDK | API 34 |
| UI stack | Material Design 3, AndroidX, RecyclerView, ConstraintLayout, and ViewPager2 |
| Data handling | Gson with durable external local storage |
| Local connectivity | UDP device discovery and TCP data transfer |
| Build system | Gradle 8.5 with Kotlin DSL and JDK 21 |

## Ready-to-Publish Description

**Masah Hisabat** is a local-first Arabic Android application for managing accounts, groups, invoices, messages, and image attachments without relying on internet connectivity. It offers a clear RTL interface, responsive phone and tablet layouts, team permissions, search, daily reporting, and durable external storage. After sign-in, devices on the same Wi-Fi network automatically merge new groups, invoices, and images in both directions without deleting local records. The app also supports authorized user synchronization and local APK updates between nearby devices, with progress feedback, event logging, integrity checks, and completion notifications.

> Developed by Mostafa ♥ Abdel Fattah.
