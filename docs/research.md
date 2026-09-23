# Google Wallet 26.37.981219770 static research

Input: `apk/com.google.android.apps.walletnfcrel_26.37.981219770.apks`

SHA-256: APKS `683BBDED7198D4D9488BF807641B03C4F5123C56FDE994CC791155EC7585C8B6`; extracted `base.apk` `BDC696AA10AF51AC9FFE09B0F2EFB88EC5E1C751CA05479DD638BDE43842263F`.

The APKS archive contains `base.apk`, `split_config.arm64_v8a.apk`, and `split_config.xxhdpi.apk`. The code and the relevant layouts are in `base.apk`; the arm64 split contains native libraries and the xxhdpi split contains density resources.

## Verified card-art path

JADX 1.5.5 output from the supplied `base.apk` shows:

```text
bmei                 Wallet payment-card model
  -> aeav.a(bmei, 1.0f)
     -> aeav.h = Uri.parse(bmdf.c)       original artwork URI
     -> aeav.d/e = bmei.h/i              display identity strings
  -> aeaw.a(awch, ImageView, aeav)
     -> aeaw.b(...)
        -> original URI + "=w" + card_art_max_request_width
        -> Wallet image loader
     -> ImageView.setImageDrawable(awch)
  -> aeaq.h(...)
     -> awch.j = original Drawable
     -> awch.invalidateSelf()

Home card carousel has the same renderer through the sibling method:

```text
rzt.f = aeav.c(akqd)
  -> rzv.c(...)
     -> aeaw.c(awch, ImageView, aeav)
```
```

The exact implementation evidence is in the decompiled classes:

- `defpackage/aeav.java`: builds card-art model and reads the `bmdf.c` URI.
- `defpackage/aeaw.java`: configures the `awch` drawable, starts the existing image load, then assigns it to the `ImageView`.
- `defpackage/aeaq.java`: receives the original loaded drawable and invalidates `awch`.
- `defpackage/awch.java`: Wallet card drawable; intrinsic size is 700x440 and it has the text/context/drawable field shape used by discovery.
- `defpackage/afmn.java`, `vuf.java`, `vty.java`, and `afrp.java`: verified callers of the same `aeaw` renderer for payment-card list/detail UI.
- `defpackage/rzv.java`: verified home-card-carousel caller; it inflates `card_carousel_card_view.xml` and calls `aeaw.c`.
- The Compose-adjacent value-proposition branch (`defpackage/acsn.java`) also receives `aeav` and sends its `ImageView` through `aeaw.a`; no separate Coil/Glide/`AsyncImage` card-art painter path was found in this APK.

Relevant layouts also confirm the target views: `card_carousel_card_view.xml`, `fop_list_prepaid_card_item.xml`, `mse_fop_list_item.xml`, `payment_card_container.xml`, and `fop_detail_card_info_header.xml` contain `ImageView` card-art slots.

The requested marker search was also checked. `PaymentCardMapper` resolves to `defpackage.absz`, the search-index document mapper; it is not the UI renderer. The raw `card_art_url`, `CARD_ART_TYPE_STATIC`, `CARD_ART_TYPE_THUMBNAIL`, `CARD_LAST_FOUR_DIGITS`, and error-marker strings are present in the supplied base DEX/resources, but the decompiled `card_art_url` references in `agyd/agyx` belong to web-redirect intent state. The executable UI chain above is therefore the hook evidence, not a guessed marker-only class.

## Hook boundary

WallArt hooks the verified renderer method shape `(Wallet card Drawable, ImageView, Wallet art model) -> void` after the original method returns. It does not change the URI, arguments, image loader, network request, card model, payment service, or any Google Play Services payment API. The custom bitmap is applied only after resolving a user-configured hash key and only if the same Wallet drawable is still attached to the same `ImageView`.

The runtime resolver enumerates DEX classes and matches observed field/method shapes, including Compose image, card-stack, and favorite-tile renderers. It does not rely on the supplied version's obfuscated `aeaw/aeav/awch` names. A missing or mismatched shape means the original renderer is left untouched.

## Identity and storage

The hook hashes the observed stable key into a 64-character card ID. It stores only that ID, a masked display label, network name, last four digits, and non-reversible image/identity fingerprints. Full PAN-like digit runs and URLs are never written to preferences or logs. Custom images stay in the module app's private `files/card_art` directory. Wallet reads them through the module's exported provider; the GMS caller is accepted only for the separately scoped card-art detail UI.

## GMS detail UI extension

The user-provided device screenshots and live Activity inspection showed that opening Suica detail launches `com.google.android.gms/.pay.main.PayActivity`, outside the Wallet process. The user explicitly approved adding `com.google.android.gms` to the LSPosed scope for this detail screen. Runtime code gates GMS hooks to the non-obfuscated `com.google.android.gms.pay.main.PayActivity` lifecycle; other GMS activities remain pass-through.

The detail renderer is discovered dynamically with the same structural signatures. GMS card identity is resolved to an existing Wallet card using a stored fingerprint (hash of normalized card-art URL and/or masked last four/type); ambiguous or unmatched identities are not recorded or replaced. This avoids creating duplicate settings entries and never stores the source URL.

Device observed GMS version: `26.34.65 (260400-981476050)`. The existing module APK exposed only Wallet as a static scope, which prevented Vector from adding GMS. The source scope list now includes both Wallet and GMS; install the updated APK, verify the framework picked up both scopes, then verify the PayActivity render path. Wallet home and its Suica/QUICPay tiles have been device-tested.

## Security non-goals

The raw `card_art_url` string also appears in an unrelated web-redirect intent path in this build, so it is not used as a guessed hook point. The module does not hook that path. `GetPayCardArtRequest`/`GetPayCardArtResponse`, TapAndPay classes, NFC/HCE, credentials, attestation, and integrity checks are outside the hook boundary; changing them would affect data or payment behavior rather than local rendering.
