# Physical controls and DISP

The user confirmed the layout and recipe flow and requires operation on the A7R II without touch. The prototype's clickable screen elements are review conveniences. All implementation mappings must be checked against actual camera key events.

| Context | Up / Down | Left / Right | Center | MENU |
| --- | --- | --- | --- | --- |
| Library | Previous/next preset or New recipe, wrapping at either end | Favorites → Built-in → My recipes | Open recipe / create | Return to shooting |
| Recipe details | Select action | Left: return; Right: focus actions | Execute action | Return |
| Editor | Move between setting rows and save actions | Adjust value/choice; choose save action | Enter name field / execute action | Leave with draft protection |
| Dialog | Select choice | Select Cancel/Confirm | Confirm choice | Cancel |
| Settings index | Select entry | Left: return to shooting; Right: no action | Open selected page | Return to shooting |
| Settings subpage | Select row / scroll read-only content | Left: return; on adjustable flash rows, Left/Right adjusts the example value | Open action / apply language / request TXT download | Return one level |
| Focus mode / area | Highlight illustrative option, wrapping at either end | Left: return; Right: no action | Select example and return to Camera settings | Cancel browsing |
| Key viewer | Observe the key | Observe the key | Observe the key | Exit to Developer tools |
| Shooting | Upper wheel position invokes DISP; other controls retain inherited shooting behavior | Preserve native/custom shooting assignments | Prototype shortcut to library; final binding must be validated | Open settings/menu |

Fn opens the recipe library in the prototype. MENU and Fn remain available when shooting information is hidden. Settings contains Save current look when the active recipe was deleted, so this recovery path remains reachable without a visible shooting overlay.

Settings opens with Flash settings, Camera settings, Language, Save issue report, About and Developer tools. Camera settings contains Focus mode / 对焦模式 and Focus area / 对焦区域. Focus choices are illustrative, independent of recipe state, and do not establish which options a camera or lens supports. Language highlights and the applied-language checkmark are separate; Center applies the focused choice. Moving with directions does not download a report or execute another action. The flash page demonstrates contextual value adjustment using example values only; the final app must use Sony's actual supported modes, range and step.

Vertical menu selection wraps from first to last and last to first. Read-only text
pages still scroll, and numeric editing respects its limits. Favorites is the
initial library category. Its preset list is one tab wide and stays on the left
when categories change; inline preset details and the scene shading are removed
from browsing. Center opens the full detail/action page. Empty Favorites offers
a Right-to-Built-in hint without applying a preset.

About links to a sources/licenses subpage. Developer tools opens Device & capabilities, Processing status, Key viewer and Recent app events. Read-only pages scroll with Up/Down. The key viewer consumes preview controls only while open, so Fn, DISP and shutter observations cannot apply recipes or simulate capture; MENU always exits. Printable keyboard characters are redacted.

## DISP behavior

The prototype illustrates **information → clean display → histogram → level**, returning to information. Press the wheel's upper DISP position or D on a computer. It remembers the illustrative mode independently of recipes. Switching modes must not change active settings, saved data, recipe selection or drafts. Inside menus the upper direction navigates and the desktop D shortcut does nothing.

The production app must **inherit Sony's original display controller and supported/enabled mode cycle**, including its LCD/EVF handling. The four browser examples are not a replacement specification for the native controller, a promise of available modes or an assertion of native ordering. Histogram and level graphics are static demonstrations, not measurements of the scene or camera orientation.

Sony declarations distinguish a DISP event from a generic Up event, even though DISP is printed at the upper wheel position. Do not copy the browser's ArrowUp shortcut into an assumed firmware key-code mapping. Handle menu directions only in the relevant screen and pass shooting events through the original converter and superclass chain. Inspect the external base APK and validate the A7R II's actual events before implementation.

In clean mode, app recipe labels and extra shooting UI are hidden. Physical MENU/Fn routes remain usable. Native focus/error/capture behavior remains Sony's responsibility in the final app; the browser only illustrates hiding its own overlays.

## Name entry and capture scope

The browser uses the computer keyboard/IME for English and Chinese names. Center enters a field; Escape finishes field entry and returns to navigation without discarding the draft. A final on-camera character grid/inherited keyboard must support directions, center selection, delete and Done/Cancel; Chinese needs a validated candidate-input method. Automatic names are usable with direction buttons but do not replace custom naming.

The prototype shutter from a menu returns to shooting; it only displays a capture animation when already shooting. Half-shutter and MENU protect dirty drafts. This is review behavior, not a claim about actual camera capture, autofocus, key timing or restoration.
