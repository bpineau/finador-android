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

## Avancement (run autonome)

- **Audit FORMAT.md ↔ code Go** : VERDICT « à jour » (12/12 zones, vecteurs KDF recalculés exacts,
  sample.ledger déchiffré). Seul point relevé : les vecteurs §9 ne sont assertés par aucun test Go.
  → *À faire (optionnel, defense-in-depth côté finador)* : ajouter un test golden Go. Différé en fin
  de run ; côté Android les vecteurs sont déjà assertés + le gate cross-impl protège le contrat.
- **Phase 0 (scaffold)** : OK. Gradle 8.13 wrapper, AGP 8.13.2, Kotlin 2.2.20, Compose BOM 2025.12.01.
  `assembleDebug` vert. `argon2kt` → **Bouncy Castle** (pure-JVM, testable en host).
- **Phase 1 (crypto + lecture)** : OK. 9 tests, vecteur KDF + sample.ledger exacts.
- **Phase 2 (writer + merge)** : OK. 20 tests. Gate **cross-impl bidirectionnel vert**
  (`scripts/crossimpl.sh`) : Go lit un .fin muté par Android (tx visible) ; Android lit un .fin créé
  par Go. Backend Sync rendu **bloquant** (pas de coroutines dans la lib ; l'UI appelle sur IO).

## Décisions à venir (complétées au fil de l'eau)
- Phase 3 : remote GitHub (Contents API), Sync (copie+state, pull/push/merge), SecretStore Keystore
  + biométrie.
