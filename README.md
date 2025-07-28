# FotoFilter

A Kotlin multi-platform based Mac application to filter/select photos after a photoshoot.

## Overview

FotoFilter is a fast, keyboard-driven tool for photographers to quickly cull and select photos after a photoshoot. Built with Kotlin Multiplatform and Jetpack Compose for Desktop, it focuses on speed and efficiency in the photo culling workflow.

### Key Features

- **Simple Culling System**: Mark photos as Keep, Discard, or Undecided with simple keyboard shortcuts
- **Batch Processing**: Quickly process hundreds of photos using keyboard-driven navigation
- **RAW + JPEG Support**: Handles CR3/CR2/NEF/ARW and JPEG pairs automatically
- **Statistics Tracking**: Shows progress with kept/discarded/remaining counts
- **Dark Mode Support**: Adapts to your macOS appearance preferences
- **Export Function**: Exports your selected photos to a new location

### TODO's

- List projects in cache on main/start window and so the user can click on them to continue or remove it from cache
- Make sure exportin only clear cache for exported project
- Add icon to app and figure out how ot make it executable
- Mac menu (change mainkt and the about page) 
- Shortcut for sidebar toggle
- Fix zoom (drag to change current area in view)
- customize shortcuts in options
- Add option to control image preview quality(make sure we use the JPEG's for the view)
- Check about using preloadImages/try to optimize image loading
- Faster start
- When click on remaining only show remaining(same for others)

## Installation

### Requirements
- macOS 10.15+ (Catalina or newer)
- Java Runtime Environment 11+ (if not bundled)

### Installation Methods

#### Direct Download
TBD write me if you need help

#### Build from Source
1. Clone the repository:
   ```
   git clone https://github.com/MartinWie/FotoFilter.git
   cd fotofilter
   ```
2. Build the application:
   ```
   ./gradlew run
   ```
3. Create a distributable package:
   ```
   ./gradlew packageDmg
   ```

## Usage

### Getting Started

1. Launch FotoFilter
2. Click "Open Photo Folder" and select a folder containing your photos
3. Navigate between photos using arrow keys (← →)
4. Use keyboard shortcuts to mark photos:
   - `K` or `Space`: Keep photo
   - `D` or `Delete`: Discard photo
   - `U`: Undecide (reset status)
5. When finished, press `E` or click "Export" to save kept photos to a new location

### Keyboard Shortcuts

| Key           | Action                      |
|---------------|----------------------------- |
| K or Space    | Mark photo as Keep          |
| D or Delete   | Mark photo as Discard       |
| U             | Reset to Undecided          |
| ← →           | Navigate between photos     |
| E             | Export kept photos          |
| H             | Show keyboard shortcuts     |

## Technical Details

FotoFilter is built using:
- Kotlin Multiplatform
- Jetpack Compose for Desktop
- MVVM Architecture
- Kotlin Coroutines for async operations

The application is designed to be lightweight and perform well even with large collections of high-resolution images.


## Contributing

Contributions are welcome! This is a hobby project but I'm open to:
- Bug reports
- Feature suggestions
- Pull requests

## Acknowledgments

- Built with [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html)
- UI powered by [Compose for Desktop](https://www.jetbrains.com/lp/compose-desktop/)

---

*This is a hobby project created to solve my own photo management needs. Feel free to use and modify as needed!*
