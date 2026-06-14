# finador-android — design (v1)

*2026-06-14 — spec issue d'un brainstorming. Client Android natif, **compatible bit-à-bit**
avec le format de fichier finador et la synchro GitHub. Le même `.fin` chiffré, dans un
dépôt privé GitHub, est utilisable indifféremment depuis le desktop (CLI/web) et depuis
Android.*

Référence normative du format : `../finador/docs/FORMAT.md` (spec v3, « implementation-grade »,
écrite explicitement pour permettre un reader/writer natif sans lire le source Go). En cas de
divergence, **`FORMAT.md` fait foi**. Référence de la synchro :
`../finador/docs/superpowers/specs/2026-06-13-github-remote-data-design.md`.

---

## 0. Objectif & périmètre

### 0.1 But

Une app Android qui lit et écrit **exactement** le même fichier `.fin` que finador, synchronisé
via le **même dépôt privé GitHub**, pour que l'utilisateur gère son patrimoine depuis le desktop
**et** depuis le mobile sur le même grand-livre, sans conversion ni divergence de format.

### 0.2 Périmètre v1 — *viewer + saisie rapide*

**Lecture (tout)** :
- Déverrouiller le `.fin`, déchiffrer, rejouer (fold) le journal → `Book`.
- Vue patrimoine : brut / impôt estimé / net, par ligne (equities/property/cash) et par `group`.
- Détail d'une position (compte × asset) : valeur, coût, +/- value, courbe.
- Performance : valeur dans le temps ; métriques TWR/XIRR/CAGR/vol/Sharpe/Sortino/maxDD
  (phase 6, « nice-to-have » de la v1).
- Affichage des labels (lecture seule).

**Écriture (saisie rapide en mobilité)** — uniquement des **transactions** sur des comptes /
assets **déjà existants** :
- `buy`, `sell`, `dividend`, `fee`, `deposit`, `withdraw`, `statement` (les 7 kinds de `tx`).
- Correction / suppression d'une transaction récente (`tx-edit`, `tx-del`).
- Chaque écriture = pull-avant → append d'un record → push-après (1 commit GitHub).

**Données marché** : l'app va chercher **ses propres cotes** (le cache marché n'est **pas**
synchronisé). Multi-source par ISIN **dès la v1** : Yahoo → FT → Morningstar, FX croisé via USD,
cache sidecar local chiffré régénérable.

### 0.3 Hors-scope v1 (non-goals)

- Création / édition de **comptes** et d'**assets**, édition de la **config**, gestion des
  **labels** (création/suppression) → restent du desktop (CLI/web).
- **Compaction** du journal (rare, full-rewrite) → desktop.
- Stockage **fichier local** (SAF/Syncthing) → **GitHub uniquement** en v1.
- iOS, widgets, notifications push, watch.

Ces opérations exclues n'empêchent rien : un `.fin` créé/structuré au desktop est pleinement
lisible et « transactionnable » depuis le mobile ; les évolutions de structure se font au desktop
puis se propagent par sync.

---

## 1. Décisions de stack

| Sujet | Décision | Raison |
|---|---|---|
| Langage | **Kotlin** (2.x stable) | natif Android, écosystème crypto/JSON mûr |
| UI | **Jetpack Compose + Material 3** | déclaratif, charts en Canvas, zéro WebView |
| Cible | **minSdk 26** (Android 8.0), **compileSdk/targetSdk 36** (build-tools 36.1.0, installés) | `java.time` + `java.util.Base64` natifs sans desugaring ; ~98 % du parc |
| Build | **Gradle Kotlin DSL + wrapper** (`./gradlew`), AGP latest stable | rien à installer hors JDK + SDK |
| Async | **Coroutines + Flow** | I/O réseau/disque, état réactif |
| DI | **manuel** (`AppContainer`, injection par constructeur) | minimaliste, testable, pas d'annotation-processing (pas de Hilt) |
| JSON | **kotlinx.serialization** | typé, contrôle des `@SerialName` (`k`/`ts`/`d`) |
| Décimaux | **`java.math.BigDecimal`** | exact, équivalent `shopspring/decimal` |
| Réseau | **OkHttp** | GitHub Contents API + Yahoo/FT/Morningstar, retry/interceptors |
| Parsing HTML | **Jsoup** | fallback FT/Morningstar (Yahoo est du JSON) |
| Charts | **Compose Canvas** (dessin maison) | courbes simples, zéro dépendance lourde (ethos finador) |
| Secrets | **Android Keystore + EncryptedSharedPreferences** (`androidx.security:security-crypto`) | équivalent macOS Keychain |

### 1.1 Crypto — dépendances

Tout est dans le JDK **sauf Argon2id** :

| Primitive | Implémentation |
|---|---|
| Argon2id | **`com.lambdapioneer.argon2kt:argon2kt`** (wrapper JNI de la lib de référence, libs natives pour tous les ABI, paramètres `t`/`m`/`p` explicites, sortie raw) — **seule dépendance crypto externe** |
| HKDF-SHA256 | maison (RFC 5869) via `javax.crypto.Mac` (HmacSHA256), ~30 lignes |
| AES-256-GCM | `javax.crypto.Cipher("AES/GCM/NoPadding")` + `GCMParameterSpec(128, nonce)` — le tag est appendé au ciphertext, **comme Go** (convention compatible) |
| SHA-256 | `java.security.MessageDigest` |
| base64 std padded | `java.util.Base64.getEncoder()/getDecoder()` |
| base64 RawURL (nom de cache) | `java.util.Base64.getUrlEncoder().withoutPadding()` |
| gzip (cache seulement) | `java.util.zip.GZIP{In,Out}putStream` |

**Garde-fou de validation** : la couche crypto est gelée par les vecteurs de `FORMAT.md §9`
(voir §10.1). Argon2id doit reproduire `master`/`keyLog`/`keyCache` exactement avant tout
développement plus haut.

---

## 2. Environnement de dev (macOS, Homebrew, gratuit) — **installé**

Environnement réellement en place (fait par l'utilisateur) :
```sh
brew install --cask temurin@21
brew install --cask android-commandlinetools
brew install --cask android-studio
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin"
sdkmanager --licenses
sdkmanager "platform-tools"
sdkmanager "platforms;android-36"
sdkmanager "build-tools;36.1.0"
sdkmanager "emulator" "system-images;android-36;google_apis;arm64-v8a"
echo no | avdmanager create avd -n test -k "system-images;android-36;google_apis;arm64-v8a"
```

Conséquences pour le projet :
- `compileSdk = 36`, `targetSdk = 36`, build-tools `36.1.0`.
- Le SDK est sous **`$(brew --prefix)/share/android-commandlinetools`** (pas `~/Library/Android/sdk`).
  Le projet écrit `local.properties` avec `sdk.dir=` pointant là, et les commandes de build
  exportent `ANDROID_HOME`/`JAVA_HOME` explicitement (le shell de build peut ne pas hériter de ces
  exports s'ils ne sont pas dans le profil).
- AVD nommé **`test`** (API 36, arm64-v8a) pour `emulator -avd test` + `installDebug`.

Build & run en CLI (une fois le SDK installé) :
```sh
./gradlew assembleDebug          # compile l'APK debug
./gradlew installDebug           # installe sur l'appareil/émulateur connecté
./gradlew test                   # tests unitaires JVM (crypto/format/merge/market)
./gradlew connectedAndroidTest   # tests instrumentés (UI, Keystore)
```

> Note : l'installation des casks et l'acceptation des licences SDK demandent l'interaction de
> l'utilisateur (mot de passe, gros téléchargement). Je scaffolde le projet pour que, une fois ces
> deux `brew install` faits, `./gradlew assembleDebug` fonctionne directement.

---

## 3. Architecture & modules

Un seul module applicatif, packages en **miroir conceptuel des `internal/*` de finador** pour
que la correspondance soit évidente et la couche « pure » (sans Android) testable en JVM seule.

```
app/src/main/kotlin/fin/android/
  crypto/      argon2, hkdf, aesgcm, sha256, base64, ids        (pur, vecteurs de test)
  domain/      Account, Asset, Tx, Label, Config, Money, TaxRule, Book, ID
  format/      ≈ store : header, record (seal/open + AAD), log (read/write),
               replay (fold), diff (diff-on-save), merge (union+LWW), reseal
  market/      Source, Yahoo, FT, Morningstar, MultiSource, FX, CacheSidecar
  remote/      GitHubBackend (Contents API), Sync (copie+state, pull/push), RemoteConfig
  valuation/   positions, cost basis, tax bases, value series, perf metrics
  data/        repositories, AppContainer (DI manuel), SecretStore (Keystore)
  ui/          Compose : onboarding, overview, position, txEntry, sync, settings + Canvas charts
```

Pour chaque unité, on doit pouvoir répondre : *que fait-elle, comment l'utiliser, de quoi
dépend-elle*. Les couches `crypto`, `domain`, `format`, `valuation`, `merge` sont **pures Kotlin**
(aucune API Android) → testables en `./gradlew test` sans appareil, et gelées par les vecteurs
de référence.

---

## 4. Compatibilité format (le cœur)

On réimplémente le reader/writer décrit par `FORMAT.md v3`. Points saillants :

### 4.1 Lecture
1. **En-tête** (ligne 1, JSON clair) : parser `fmt`/`v`/`kdf`/`t`/`m`/`p`/`salt`/`id`.
   - Refuser si `fmt != "finador-ledger"`, si `v` inconnu (≠ 3), si `kdf != "argon2id"`.
   - **Vérifier les bornes Argon2 AVANT dérivation** : `1≤t≤16`, `8≤m≤1048576` (KiB), `1≤p≤16`,
     `len(salt)==16`, `len(id)==16` (anti memory-bomb sur en-tête forgé).
   - `hdrHash = SHA-256(octets bruts de la ligne 1)` (hacher la **ligne on-disk**, pas une
     re-sérialisation).
2. **Dérivation** : `master = Argon2id(pwd, salt, t, m, p, 32)` ;
   `keyLog = HKDF-SHA256(master, nil, "finador-ledger-v2", 32)` ;
   `keyCache = HKDF-SHA256(master, nil, "finador-cache-v2", 32)`.
3. **Records** (lignes 2…N+1) : `base64( nonce[12] ‖ AES-GCM(plaintext, AAD) )` avec
   `AAD = hdrHash[32] ‖ uint64_be(seq) ‖ prevTag[16]` (premier record : `prevTag = 16×0x00`).
   Le « tag » de chaînage = 16 derniers octets de la sortie GCM précédente.
4. **Trailer** (dernière ligne) : déchiffrer sous `AAD_head = hdrHash ‖ "finador-head" ‖
   uint64_be(count)`, vérifier `count == nb de records lus` **et** `head == tag du dernier
   record` (détection de troncature/altération).
5. **Replay (fold)** en ordre fichier : upsert/tombstone par `id` (config par `key`).
   `tx-edit == tx` (upsert), tombstones suppriment. **Un `k` inconnu = erreur dure** (jamais
   ignoré — pourrait cacher de l'argent). **Champs inconnus d'un `k` connu = tolérés** (et
   round-trippés via la ligne verbatim).

### 4.2 Écriture (diff-on-save, append-only)
- On conserve la **ligne base64 verbatim** de chaque record déjà persisté.
- À la sauvegarde : calculer le **diff** snapshot↔Book → records minimaux (créé/changé ⇒ record ;
  supprimé ⇒ `*-del`), ordre `config` → comptes → assets → tx (définitions avant références) ;
  **ré-émettre les lignes existantes octet pour octet**, appender les nouveaux records scellés
  (chaîne continue : `seq` continue, `prevTag` = dernier tag courant), `ts` = UTC maintenant,
  resceller le trailer. En v1 le mobile ne crée que des `tx`/`tx-edit`/`tx-del`, mais le moteur
  de diff/scellage est générique.
- **Écriture atomique** dans la copie de travail : `tmp` → `fsync` → rotation `.bak` → `rename`.

### 4.3 Détails de sérialisation à respecter
- Décimaux et quantités = **chaînes JSON** (`"9000"`, `"42.50"`) → `BigDecimal`. `Money =
  {"amount":"…","ccy":"…"}`.
- `ts` = **RFC 3339 nanosecondes, UTC** (`2026-06-13T13:36:03.896575Z`).
- Enums (`tax`, `kind` d'asset, `kind` de tx) sérialisés en **chaînes** (MarshalText côté Go).
- **`id`** (comptes/assets/tx/labels) = `domain.NewID` : `uint48_be(unixMillis)[6] ‖ rand[8]` →
  **Crockford base32 minuscule sans padding** (alphabet `0123456789abcdefghjkmnpqrstvwxyz`,
  sans `i l o u`) → **23 caractères**. À réimplémenter (pas de Crockford en JDK).
- L'**ordre des clés JSON est indifférent** (le reader parse, ne suppose rien) → notre
  sérialisation n'a pas à être byte-identique à celle de Go ; elle doit juste être **valide et
  sémantiquement correcte** (les lignes existantes, elles, sont ré-émises verbatim).

---

## 5. Synchronisation GitHub (`remote/`)

Le `.fin` **vit dans un dépôt privé GitHub** ; le remote n'est qu'un **transport**. Réimplémente
le modèle de la spec remote de finador.

### 5.1 Backend — GitHub Contents API (HTTPS pur, pas de binaire git)
- `GET /repos/{owner}/{repo}/contents/{path}?ref={branch}` (`Authorization: Bearer <PAT>`,
  `Accept: application/vnd.github+json`) → `{content: base64, sha}`. **Gotcha** : `content` GitHub
  contient des retours-ligne tous les 60 car. → **strip whitespace avant base64-decode**. 404 →
  `ErrRemoteMissing`.
- `PUT /repos/{owner}/{repo}/contents/{path}` body `{message, content: base64(octets du .fin),
  sha (omis si création), branch}` → nouveau `sha`. 409/422 sur `sha` périmé → `ErrRemoteConflict`.
- Chaque `PUT` = **un commit** → historique visible, petits deltas (append-log).
- **Caveat** : Contents API plafonne ~1 Mo ; le `.fin` (grand-livre seul, cache exclu) reste très
  en-dessous. Si un jour ça approche → API Git Blobs (hors v1, noté).
- Client OkHttp : timeout + 1 retry sur 5xx/429. Erreur réseau/DNS → **hors-ligne** (distinct des
  4xx d'auth).

Interface (seam, calquée sur `internal/remote`) :
```kotlin
sealed interface RemoteError { object Conflict; object Missing; data class Auth; data class Offline }
interface Backend {
    suspend fun fetch(): Result<Pair<ByteArray, Version>>   // Version = sha opaque
    suspend fun push(data: ByteArray, base: Version?, msg: String): Result<Version>
    fun describe(): String
}
```

### 5.2 Couche sync — copie de travail + état
- **Copie de travail** : `filesDir/checkout/<hash(owner/repo/path)>.fin`.
- **État sidecar** : `…<hash>.state.json` = `{ "sha": "<dernier sha distant connu>",
  "lastPull": "<RFC3339>", "dirty": false }`. `dirty=true` = changements non poussés (hors-ligne).
- **Lecture** (`openForRead`) : si en ligne et `now-lastPull > readPullAfter` (défaut 1h, configurable)
  ou `dirty` → `fetch` → écrire la copie → MAJ `sha`+`lastPull` ; sinon utiliser la copie. Puis
  `format.open(copie, passphrase)`.
- **Mutation** (`mutate`, saisie d'une tx) : `fetch` frais d'abord (MAJ copie+`sha`) → `format.open`
  → append du record → `save` (copie) → `push(copie, sha, msg)` → succès : MAJ `sha`, `dirty=false`.
  - `ErrRemoteConflict` → **re-`fetch`** dans un temp → **merge** (§5.3) → re-`push` (boucle bornée).
  - Hors-ligne → `dirty=true`, **succès local** + avertissement « non poussé », push au prochain
    accès en ligne.
- **`sync` manuel** (bouton) : force `fetch` ; si `dirty`, push (avec résolution de conflit) ;
  sinon rafraîchir. Affiche un résumé.
- Message de commit auto : `finador-android: <action> (<date heure>)`.

### 5.3 Conflits & merge (`format/merge.kt`)
Réimplémente `internal/store/merge.go` :
- **Refuser** si l'`id` d'en-tête diffère (ledgers différents).
- Grouper tous les records des deux copies par `(class, id)` (config par `key`), trier par `ts`
  (compare lexicographique RFC3339Nano = chronologique), **last-writer-wins** au `ts` max.
- Records identiques (même `k` + mêmes octets `d`) = pas un conflit.
- **Vrai conflit** = même `ts` (à la nanoseconde) + payloads différents → cas quasi inexistant
  pour un utilisateur seul sur 2 appareils. UI : petit dialogue « garder cette version / la
  distante » ; défaut documenté si non interactif.
- Résultat : log rescellé (chaîne fraîche, `ts` de chaque gagnant préservé), écrit atomiquement.

### 5.4 Configuration de la source (Android)
Pas de `~/.config/finador/` sur mobile → **`filesDir/config.json`** :
```json
{ "source": "github",
  "github": { "owner": "bpineau", "repo": "finador-data", "path": "portfolio.fin", "branch": "main" },
  "readPullAfter": "1h" }
```
(Édité par l'écran Settings.) Le **PAT** ne vit jamais ici — uniquement dans le Keystore.

---

## 6. Données marché (`market/`)

Le **cache marché n'est pas synchronisé** : un appareil fraîchement synchronisé a le grand-livre
mais **aucun prix**. L'app va donc chercher ses cotes elle-même.

- **Multi-source par ISIN, ordre de fallback** : **Yahoo** (JSON, source primaire) → **FT** →
  **Morningstar** (HTML via Jsoup). Couvre les fonds FR/LU absents de Yahoo. (Les FCPE AMF
  restent manuels côté desktop ; le mobile lit la dernière valeur connue / `statement`.)
- **FX** : croisé via **USD** (comme finador).
- **Cache sidecar local chiffré, régénérable** (`cacheDir/finador/<id-RawURL>.cache`), format
  `FORMAT.md §7` : `"FINCACHE2" ‖ nonce[12] ‖ AES-GCM( gzip(JSON(MarketData)), AAD="FINCACHE2" )`
  sous **`keyCache`**. Compatible avec le sidecar desktop (mais jamais poussé).
- Un cache absent/illisible/périmé n'est **pas une erreur** : on refetch. Rafraîchissement à
  l'ouverture (throttlé) + bouton « refresh » manuel.

> Réutilisation : le format `MarketData` et la logique multi-source répliquent `internal/market`.
> On peut tester chaque source avec MockWebServer + des fixtures HTML/JSON capturées.

---

## 7. Valorisation & performance (`valuation/`)

Recalculé depuis les transactions foldées (rien n'est stocké), comme finador :
- **Positions** par (compte × asset), quantités, coût (cost basis), valeur courante (cote × qty,
  converti dans la devise de référence via FX).
- **Impôt estimé** selon la règle du compte : `gains:N%` taxe `max(0, valeur − base de
  contributions)` ; `value:N%` taxe toute la valeur ; `none` rien. Affichage **brut / impôt / net**.
- **Séries de valeur** dans le temps → courbes (Compose Canvas).
- **Métriques** (phase 6) : TWR, XIRR, CAGR, volatilité, Sharpe, Sortino, maxDD — par période /
  scope. Réimplémente `internal/perf`.

---

## 8. UI (Compose) — écrans v1

1. **Onboarding (une seule fois)** : saisir `owner/repo` (+ path, branch) ; **coller le PAT
   GitHub** dans un champ texte (bouton « coller », masqué après saisie) → **stocké au Keystore**,
   plus jamais à retaper ; saisir la **passphrase** du `.fin` une fois → **stockée au Keystore
   derrière BiometricPrompt**. Premier pull → déverrouillage. **Aux ouvertures suivantes : seule
   l'authentification biométrique** (empreinte/visage) déverrouille — ni PAT ni passphrase à
   retaper. Repli code/PIN de l'appareil si la biométrie échoue.
2. **Overview (patrimoine)** : total brut/impôt/net ; lignes equities/property/cash ; arbre par
   `group` ; sparkline globale ; bandeau état de sync (à jour / non poussé / hors-ligne).
3. **Détail position** : valeur, coût, +/- value, labels (lecture), courbe ; liste des
   transactions de la position.
4. **Saisie de transaction** : choisir compte (existant) → kind → asset (si applicable) → date,
   qty, montant, note → valider = `mutate` (pull→append→push) + feedback.
5. **Sync** : bouton « synchroniser maintenant », dernier pull, état `dirty`, conflits résolus.
6. **Settings** : repo/branch/path, `readPullAfter`, re-login token, « oublier » (purge Keystore),
   refresh cotes, à-propos/version de format (refuse `v` inconnu).

Charts : dessin **Canvas** maison (ligne + sparkline), pas de WebView ni lib lourde (ethos finador :
« zero external resources »).

---

## 9. Sécurité & gestion d'erreurs

- **Repo = uniquement le `.fin` chiffré** : même fuité, opaque. Le **PAT** est un *fine-grained
  token scopé à ce seul dépôt* (Contents: R/W). On le **colle** dans un champ à l'onboarding, il
  est **stocké au Keystore** (EncryptedSharedPreferences ; jamais en clair, jamais loggué, plus
  jamais à retaper). Re-login depuis Settings si on le révoque/régénère.
- **Passphrase** : saisie **une seule fois**, **stockée au Keystore derrière BiometricPrompt**.
  Ensuite, **déverrouillage par biométrie seule** (repli code/PIN appareil) — jamais de re-saisie,
  jamais sur disque en clair. La clé KDF n'est dérivée qu'après déverrouillage biométrique.
  Mauvaise passphrase et fichier altéré = **même erreur** « bad password / corrupt file »
  (indistinguables par design). « Oublier » dans Settings purge PAT + passphrase du Keystore.
- **Refus stricts** : `v` inconnu → refus net ; `k` inconnu → erreur dure ; bornes Argon2 avant
  dérivation ; trailer/chaîne invalides → refus (troncature/altération).
- **Réseau** : 401/403 → « token invalide ou permissions insuffisantes » (≠ hors-ligne) ;
  réseau/DNS → hors-ligne (chemin `dirty`). Contents API ~1 Mo → caveat documenté.
- **Permissions Android** : `INTERNET` seulement. Pas de permission stockage (GitHub-only ;
  copie de travail + cache en stockage **privé** de l'app : `filesDir`/`cacheDir`).

---

## 10. Tests (TDD)

La couche pure est gelée par des **vecteurs de référence** avant toute UI.

### 10.1 Vecteurs crypto/format (golden, `FORMAT.md §9`)
- **KDF** : `pwd = "correct horse battery staple"`, `salt = 000102…0f`, `t3 m65536 p4` →
  `master = 853b27…b49e`, `keyLog = 156457…167b`, `keyCache = 7c39dd…0b3a` (égalité exacte).
- **`sample.ledger`** (`../finador/docs/format-testdata/`) : déchiffrer bout-en-bout (en-tête →
  records → trailer) et reproduire comptes/assets/tx documentés.

### 10.2 Round-trip & cross-implémentation
- Écrire un ledger côté Android, le relire côté Android (replay identique).
- **Cross-impl** : ouvrir avec le **CLI finador** un `.fin` muté par Android (et inversement) —
  test manuel/CI documenté (le vrai critère de « compatibilité »).
- **Merge** : divergences synthétiques (adds/deletes/edits concurrents, LWW, vrai conflit) =
  parité avec `merge_test.go`.

### 10.3 Réseau & UI
- GitHub Contents (fetch/push/conflict/missing) et sources marché : **MockWebServer** + fixtures.
- Sync (pull-avant/push-après, offline `dirty`, conflit→merge) : backend factice.
- Écrans : Compose UI tests ; Keystore : test instrumenté.

---

## 11. Phasage

1. **Format core (offline, TDD)** : `crypto/` (Argon2id/HKDF/AES-GCM/SHA/base64) → vecteur KDF
   vert ; `domain/` + `format/` lecture (header/record/log/replay) → `sample.ledger` vert. Pur
   Kotlin, zéro UI.
2. **Write path** : diff-on-save, scellage, reseal, write atomique sur copie de travail ;
   round-trip ; `merge` (union+LWW+garde `id`).
3. **Remote GitHub + secrets** : `GitHubBackend` (Contents API) + `Sync` (copie + `state.json`,
   pull-avant/push-après, offline `dirty`, conflit→merge) + **`SecretStore`** (Keystore /
   EncryptedSharedPreferences : PAT **et** passphrase) avec **déverrouillage BiometricPrompt** dès
   ce socle (exigence : PAT collé+stocké, passphrase stockée une fois puis biométrie seule).
   MockWebServer.
4. **Marché** : Yahoo + FT + Morningstar (multi-source par ISIN), FX via USD, cache `FINCACHE2`.
5. **UI Compose** : onboarding (coller PAT, saisir passphrase une fois, opt-in biométrie),
   déverrouillage biométrique au lancement, overview, détail position, saisie tx, sync, settings
   (re-login, « oublier ») + charts Canvas.
6. **Perf + polish** (good-to-have) : TWR/XIRR/CAGR/vol/Sharpe/Sortino/maxDD ; indicateurs
   hors-ligne fins ; raffinements visuels.

v1 « expédiable » = phases 1–5 (biométrie incluse dès la phase 3) ; la 6 enrichit.

---

## 12. Critères de réussite

1. Déchiffre `sample.ledger` et reproduit le vecteur KDF **exactement**.
2. Ouvre, depuis le mobile, un `.fin` réel créé au desktop ; affiche brut/impôt/net cohérents.
3. Saisie d'une `tx` au mobile → pull-avant/push-après → **un commit** GitHub ; le **CLI finador**
   rouvre le fichier sans erreur (compatibilité bidirectionnelle prouvée).
4. Conflit distant → réconcilié via `merge` sans perte ; hors-ligne → écriture locale + push différé.
5. Le repo ne contient que le `.fin` **chiffré** ; le cache marché reste **local** (jamais poussé).
6. Cotes récupérées multi-source (Yahoo→FT→Morningstar) ; valorisation des fonds FR/LU.
7. 401/403 distincts du hors-ligne ; `v`/`k` inconnus refusés ; PAT jamais en clair.
8. `./gradlew test` vert (couche pure gelée par vecteurs) ; build APK debug OK.

---

## 13. Évolutions futures (hors v1)

- Création/édition comptes & assets, config, labels (parité d'écriture complète).
- Stockage fichier local (SAF) / autres hôtes (interface `Backend` déjà en place).
- API Git Blobs si le `.fin` dépasse ~1 Mo.
- Compaction mobile ; widgets ; iOS via extraction d'un cœur KMP (la couche pure y est déjà
  isolée).
