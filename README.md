# DanielTheManiel's Morphe Patches

Made for me, with the help of AI

## ❓ About

Patches for apps I like.

<!-- TODO: Update this about section with a brief introduction/summary about this repo and what it offers. -->

**NOTE FOR DeFit++:** This patch breaks google sign in since firebase is expecting a specific certificate. Instructions to get around it will be posted soon, but for now you can use my prebuilt version.

### How to use these patches

Click here to add these patches to Morphe: https://morphe.software/add-source?github=Dan1elTheMan1el/Morphe-Patches

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.0.0](https://github.com/Dan1elTheMan1el/Morphe-Patches/releases/tag/v1.0.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;4 patches total
<details open>
<summary>📦 DeFit - Debugger of Fitness Apps&nbsp;&nbsp;•&nbsp;&nbsp;4 patches</summary>
<br>

**🎯 Supported versions:**

| 0.8.2a |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Custom Branding](#custom-branding) | Changes DeFit's app name, header title, and displayed version. | • App name<br>• Top bar text<br>• Version |
| [Material You Theme](#material-you-theme) | Uses Android 12+ wallpaper-derived colors and enables safe system Force Dark without changing DeFit's AppCompat theme parent. |  |
| [Remove Bottom Banner Ad](#remove-bottom-banner-ad) | Removes DeFit's bottom banner-ad container from the main layout. |  |
| [Unlimited Time Bypass](#unlimited-time-bypass) | Removes the ad requirement and grants maximum active time when the button is pressed. |  |

</details>

<!-- PATCHES_END -->

### 🛠️ Building locally

- Run `./gradlew buildAndroid`
- The built patches .mpp file is found in `patches/build/libs/patches-*.mpp`
- Patch the mpp file using [Morphe-Desktop](https://github.com/MorpheApp/morphe-desktop)
  like any other patch bundle.

See the [Morphe documentation](https://github.com/MorpheApp/morphe-documentation) for more information.

## 📜 License

DanielTheManiel's Morphe Patches are licensed under the [GNU General Public License v3.0](LICENSE)
