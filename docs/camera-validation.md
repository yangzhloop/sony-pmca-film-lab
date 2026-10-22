# Historical 0.1.0 diagnostic APK desktop validation

These results describe the first diagnostic build. See [0.2 native app validation](native-app-validation.md) for the current implementation.

Artifact: `dist/FilmLab-diagnostic.apk`, version `0.1.0-diagnostics`.

SHA-256: `041c269294016d22a39819575c7fc48d78a752a782abde8474e38cc97c6fd398`

Build ID: `a8ca8ec155392184ced053d5292ab4dc92d0c80539b663ba62481c9133522407`

The [build receipt](../dist/FilmLab-diagnostic.build.json) records source/input/tool hashes and verification details. Two builds in separate empty work directories, using the same cached reference APK and local signing identity, produced identical APK bytes. This does not imply identical output with a newly generated signing key or a byte-identical rebuild of the upstream release.

| Check | Result |
| --- | --- |
| Original additions compile against complete Android API 10 definitions | Pass; Java 6 class target |
| DEX format and size | Single DEX `035`; 17,222 method references |
| Legacy signing | v1 signature verified with minimum/maximum verifier API 10 |
| Manifest independently decoded by Apktool | Separate Film Lab package/launcher; original application and shooting class retained |
| Original resource/asset content | 682 original archive entries preserved; only manifest, DEX, resource package header and signatures replaced |
| Resource table change | Fixed package-name field only; resource IDs/content preserved |
| Diagnostic hooks | Five classes; native key, DISP and flash handlers unchanged |
| Film Studio profiles | 120 compiled arrays match the verified published release; fifteen icon links checked |
| Still-photo preparation | Record-mode value `1`; added movie menus absent; eleven upstream preview-flow checks pass |
| Build-tool regression tests | Six tests pass, including rejection of unsupported modern Android API and corrupt cached downloads |
| Report store | Eight scenarios, 73 assertions pass: redaction, UTF-8, rotation, reload, crash snapshot, collisions, export verification, retry and nonblocking private logging during stalled card export |
| Physical-key mapping logic | Forty assertions pass across reflected Sony constants and fallback mappings |

The original manifest has no SDK declaration; its implicit minimum is unchanged. API 10 compilation and signature checks do not turn that into an explicit manifest minimum or a hardware-compatibility claim.

Diagnostics observe look requests, accepted/rejected calls, existing native parameter readbacks, normal still-capture requests and shutter callbacks. They make no additional camera queries. Cached readbacks carry age/order evidence; accepted calls or shutter callbacks do not verify a saved JPEG. Self-timer and other capture paths may not produce every observation. Missing observations, the confirmed active look and native DISP state remain unknown where no reliable hook exists.

No camera was attached to this build environment. Installation, actual A7R II button codes, Chinese glyphs, LCD/EVF DISP, flash availability, report-to-card mapping and saved-image appearance require the [hardware checklist](a7rii-test-checklist.md). Full custom-recipe management and bilingual inherited shooting menus remain later implementation work.

The APK is a local generated artifact and is ignored by Git. Original source, scripts, documentation and the JSON receipt are reviewable in this checkout; no release has been published.
