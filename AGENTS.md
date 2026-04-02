# AGENTS.md

Legado TTS Android Application - Coding guidelines for agentic development.

## Project Overview

This is an Android novel reader app with built-in EdgeTTS and DoubaoTTS, including a Vue.js web module for bookshelf management.

**Tech Stack:**
- Android app: Kotlin/Java, Gradle, Room, Coroutines
- Web module: Vue 3, TypeScript, Vite, Pinia
- Build tools: Gradle (Android), pnpm/Vite (Web)

---

## Build Commands

### Web Module (Vue.js/TypeScript)

```bash
cd modules/web

# Development
pnpm dev                    # Start Vite dev server

# Build
pnpm build                  # Full build with type-check
pnpm build-only             # Build without type-check

# Lint & Format
pnpm lint:fix               # Run ESLint and auto-fix
pnpm format                 # Format with Prettier

# Preview
pnpm preview                # Preview production build
```

**Requirements:**
- Node.js >= 20
- pnpm >= 9

### Android Module (Kotlin/Java)

```bash
# Build all variants
./gradlew build

# Build specific variants
./gradlew assembleApprelease          # Release build
./gradlew assembleAppreleaseA          # Coexistence build (共存)

# Optimized build (CI default)
./gradlew assembleApprelease --build-cache --parallel --daemon --warning-mode all

# Clean
./gradlew clean
```

**Build Variants:**
- `release` - Standard release (applicationId: `io.legado.app.release`)
- `releaseA` - Parallel/coexistence build (applicationId: `io.legado.app.releaseA`)
- `debug` - Debug build (applicationId: `io.legado.app.debug`)

---

## Lint Commands

### Web Module

```bash
cd modules/web
pnpm lint:fix              # Auto-fix ESLint issues
pnpm format                 # Format with Prettier
```

**Config:**
- ESLint: `modules/web/eslint.config.mjs`
- Prettier: `modules/web/.prettierrc.json`
- Lints: `**/*.{ts,mts,tsx,vue}`

### Android Module

```bash
# Run lint checks
./gradlew lint
./gradlew lintDebug
./gradlew lintRelease
```

**Note:** Android lint is enabled in `app/build.gradle` with dependency checking.

---

## Test Commands

### Web Module

No test framework currently configured. Tests should be set up using Vitest or Jest.

### Android Module

**Test Framework:** JUnit (Android Testing Support Library)

**Run all unit tests:**
```bash
./gradlew test
./gradlew testDebugUnitTest
```

**Run all instrumented tests (requires device/emulator):**
```bash
./gradlew connectedAndroidTest
./gradlew connectedDebugAndroidTest
```

**Run single test class:**
```bash
# Unit tests
./gradlew test --tests "io.legado.app.ExampleUnitTest"
./gradlew test --tests "io.legado.app.JsTest"

# Instrumented tests
./gradlew connectedAndroidTest --tests "io.legado.app.ExampleInstrumentedTest"
./gradlew connectedAndroidTest --tests "io.legado.app.HttpTest"
```

**Run single test method:**
```bash
./gradlew test --tests "io.legado.app.ExampleUnitTest.addition_isCorrect"
./gradlew connectedAndroidTest --tests "io.legado.app.ExampleInstrumentedTest.testContentProvider"
```

**Run tests by pattern:**
```bash
./gradlew test --tests "*JsTest"              # All JsTest methods
./gradlew test --tests "*Test.addition_*"     # Methods matching pattern
*```

---

## Code Style Guidelines

### TypeScript/JavaScript

**Formatting:**
- Indentation: 2 spaces
- Quotes: Single quotes
- Semicolons: No semicolons
- Trailing whitespace: Trimmed
- Final newlines: Required

**Import ordering:**
```typescript
// 1. Third-party libraries
import { defineStore } from 'pinia'
import axios from 'axios'

// 2. Internal modules with @/ alias
import API from '@api'
import { useBookStore } from '@store'

// 3. Type imports (separate group)
import type { Book, Chapter } from '@/book'
import type { WebReadConfig } from '@/web'
```

**Type annotations:**
- Use explicit typing for function parameters
- Separate type imports with `import type`
- Prefer `interface` for object shapes

```typescript
const validatorHttpUrl = (
  http_url: string | URL,
  allowedProtocols: string[] = ['https:', 'http:'],
): boolean => {
  // implementation
}
```

**Error handling:**
```typescript
try {
  const url = new URL(http_url)
  // processing
} catch {
  return false
}
```

**Naming conventions:**
- Files: `kebab-case.ts` for utilities, `PascalCase.ts` for components
- Functions/variables: `camelCase`
- Classes/interfaces: `PascalCase`
- Constants: `UPPER_SNAKE_CASE`

### Kotlin

**Formatting:**
- Indentation: 4 spaces
- Brace style: K&R (opening brace on same line)
- No strict line length limit

**Import ordering:**
```kotlin
// 1. Android framework imports
import android.content.Context
import androidx.annotation.NonNull

// 2. Project imports (io.legado.app.*)
import io.legado.app.BuildConfig
import io.legado.app.constant.AppLog

// 3. Third-party libraries
import kotlinx.coroutines.CoroutineScope
import java.net.HttpURLConnection
```

**Type annotations:**
- Prefer type inference (`val`, `var`) where type is obvious
- Explicit types for function parameters and returns
- Nullable types: explicit `?` suffix

```kotlin
fun getFileName(fileUrl: String, headerMap: Map<String, String>? = null): String? {
    return kotlin.runCatching {
        // implementation
    }.getOrNull()
}
```

**Error handling (preferred idiom):**
```kotlin
// Use runCatching for Kotlin
return kotlin.runCatching {
    // code that might throw
}.onFailure {
    AppLog.put("Error message", it)
}.getOrNull()

// Use @Synchronized for thread safety
@Synchronized
fun sharedMethod() {
    // implementation
}
```

**Naming conventions:**
- Files: `PascalCase.kt`
- Classes/objects: `PascalCase`
- Functions/properties: `camelCase`
- Constants: `UPPER_SNAKE_CASE`
- Private members: `camelCase` (no underscore prefix)

**Suppress annotations:**
```kotlin
@Suppress("MemberVisibilityCanBePrivate")
@Suppress("unused")
```

### Java

**Formatting:**
- Indentation: 4 spaces
- Brace style: K&R
- Full type declarations (no var/let)

**Import ordering:**
```java
// 1. Android SDK
import android.util.Log;
import androidx.annotation.NonNull;

// 2. Third-party libraries
import org.w3c.dom.Document;

// 3. Internal project
import me.ag2s.epublib.domain.EpubBook;
```

**Error handling:**
```java
try {
    // code
} catch (Exception e) {
    Log.e(TAG, e.getMessage(), e);
}
```

**Naming conventions:**
- Files: `PascalCase.java`
- Classes: `PascalCase`
- Methods/variables: `camelCase`
- Constants: `UPPER_SNAKE_CASE`

---

## Documentation Style

### Kotlin (KDoc)

```kotlin
/**
 * 根据网络url获取文件信息文件名
 *
 * @param fileUrl 文件URL
 * @param headerMap 请求头
 * @return 文件名，失败返回null
 */
fun getFileName(fileUrl: String, headerMap: Map<String, String>? = null): String? {
    // implementation
}
```

### TypeScript (JSDoc)

```typescript
/**
 * @type {string} localStorage保存自定义阅读http服务接口的键值
 */
export const baseURL_localStorage_key = 'remoteUrl'

/**
 * 验证HTTP URL格式
 * @param http_url - URL字符串或URL对象
 * @param allowedProtocols - 允许的协议列表
 * @returns 是否为有效的HTTP URL
 */
export const validatorHttpUrl = (http_url: string | URL, allowedProtocols: string[] = ['https:', 'http:']): boolean => {
  // implementation
}
```

### Java (Javadoc)

```java
/**
 * Reads an epub file.
 *
 * @author paul
 * @see EpubBook
 */
public class EpubReader {
    // implementation
}
```

**Note:** Comments may be in Chinese or English. Maintain consistency within files.

---

## Project Structure

### Android App (`app/`)

```
app/src/main/java/io/legado/app/
├── api/              # API interfaces
├── base/             # Base classes (activities, fragments)
├── constant/         # Constants and enums
├── data/             # Data layer (entities, DAOs, database)
├── exception/        # Exception types
├── help/             # Helper classes (TTS, HTTP, crypto)
├── lib/              # Third-party libraries (icu4j)
├── model/            # Business logic (books, RSS, web books)
├── receiver/         # Broadcast receivers
├── service/          # Services (read aloud, cache book)
├── ui/               # UI components
└── utils/            # Utility functions
```

### Web Module (`modules/web/`)

```
modules/web/src/
├── api/              # Axios API client
-├── components/       # Vue components
├── pages/            # Page components (bookshelf, source, etc.)
├── store/            # Pinia stores
├── utils/            # Utility functions
└── App.vue           # Root component
```

---

## Special Considerations

### JavaScript Book Source Rules

Custom book sources use JavaScript with Rhino engine. Patterns documented in `app/src/main/assets/web/help/md/ruleHelp.md`:

- Async/concurrency warnings for shared global variables
- Header keys: Title case (User-Agent, Referer)
- Proxy configuration formats
- Base64 encoding patterns

### Version Management

- Version format: `3.YY.MMDDHH` (e.g., `3.26.010214`)
- Version code: `10000 + gitCommits`
- Auto-generated from git commit count

### Thread Safety

Kotlin code uses `@Synchronized` for thread-safe operations, especially in:
- `ReadBook` object (chapter loading)
- Shared state management
- Concurrent access to collections

### Room Database

- Schema export enabled for migration testing
- Schemas located in `app/schemas/`
- Test assets include exported schemas for validation

---

## CI/CD Integration

**GitHub Actions:** `.github/workflows/`

- `test.yml` - Main CI workflow (builds Android app, runs tests)
- `web.yml` - Web module CI (builds and syncs to Android assets)
- `release.yml` - Release management
- `cronet.yml` - Cronet library updates

**Build artifacts:**
- APK files uploaded as GitHub artifacts
- ProGuard mapping files preserved for de-obfuscation
- Pre-releases created on GitHub for beta testing

---

## Notes for Agents

1. **Language:** Repository uses mixed Chinese/English documentation
2. **Web dependencies:** Use `@/` alias for internal imports
3. **Kotlin null safety:** Always use nullable annotations (`?`) explicitly
4. **Error handling:** Prefer language idioms (`runCatching` in Kotlin, try-catch in TypeScript)
5. **Concurrency:** Use `@Synchronized` for multi-threaded access in Kotlin
6. **Testing:** Room tests require schema exports; run `connectedAndroidTest` for device tests
7. **Build variants:** Test both `apprelease` and `appreleaseA` when building
8. **TTS modifications:** EdgeTTS and DoubaoTTS are core features - test thoroughly
9. **Web sync:** Web assets are synced to Android via build process - build web module before Android
10. **Memory vs disk caching:** This fork uses memory caching for TTS audio streams (key modification)
