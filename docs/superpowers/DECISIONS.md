# Décisions — run autonome finador-android

Journal des décisions prises en autonomie (l'utilisateur a demandé un build A→Z sans
questions ni confirmations). Chaque décision non triviale est tracée ici.

## 2026-06-14 — cadrage

- **Stack** : Kotlin + Jetpack Compose (Material 3), pas de KMP (Android pur, iOS non demandé).
- **Cible** : compileSdk/targetSdk **36**, build-tools **36.1.0**, minSdk **26** — pour coller à
  l'env installé par l'utilisateur (android-commandlinetools, `platforms;android-36`).
- **SDK** : `ANDROID_HOME=$(brew --prefix)/share/android-commandlinetools` ; le projet écrit
  `local.properties` et les commandes de build exportent `ANDROID_HOME`/`JAVA_HOME` explicitement.
- **DI** : manuel (`AppContainer`), pas de Hilt (minimalisme, pas d'annotation-processing).
- **Crypto** : JDK partout sauf **Argon2id** → lib `com.lambdapioneer.argon2kt:argon2kt`.
  HKDF-SHA256 implémenté maison (RFC 5869) pour éviter une dépendance. Validé contre
  `FORMAT.md §9.1`.
- **Réseau** : OkHttp + kotlinx.serialization ; **Jsoup** pour FT/Morningstar (Yahoo = JSON).
- **Charts** : Compose Canvas maison (pas de lib lourde).
- **Secrets/auth** (exigence explicite) : PAT GitHub **collé puis stocké** au Keystore ;
  passphrase saisie **une fois** puis **déverrouillage par biométrie seule** (BiometricPrompt) —
  intégré dès la **phase 3** (socle), pas reporté.
- **Perf** (TWR/XIRR/Sharpe…) : **good-to-have**, phase 6 ; v1 expédiable = phases 1–5.
- **Storage** : **GitHub uniquement** en v1 (pas de fichier local / SAF).
- **Prérequis correctness** : audit `FORMAT.md` ↔ code Go lancé avant d'écrire la couche format.

## Décisions à venir (complétées au fil de l'eau)
- (suite du run consignée ici)
