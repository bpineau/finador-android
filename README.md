# finador-android

Client **Android natif** (Kotlin / Jetpack Compose) compagnon de
[finador](../finador) (desktop CLI + web). Il lit et écrit **le même fichier `.fin`
chiffré**, synchronisé via un **dépôt privé GitHub**, donc tu gères ton patrimoine
indifféremment depuis le desktop et le mobile.

- **Périmètre v1** : lecture complète (valeur brut/impôt/net, gains, détail par actif)
  + saisie rapide de transactions. Création de comptes/assets reste au desktop.
- **Compatibilité** : bit-à-bit avec `finador/docs/FORMAT.md` (vérifiée par les vecteurs
  de test + un test cross-implémentation avec le binaire Go).
- **Stockage** : GitHub uniquement (le `.fin` chiffré ne quitte jamais le dépôt en clair) ;
  cotes marché récupérées sur l'appareil (Yahoo → FT → Morningstar), cache local chiffré.

---

## 1. Préparer l'environnement de dev (macOS, gratuit)

> Pour un dev **peu habitué à Android** : tu n'installes **pas** Gradle ni Android Studio
> obligatoirement — le projet embarque un *wrapper* (`./gradlew`) qui télécharge la bonne
> version de Gradle, et la ligne de commande suffit pour compiler/installer. Android Studio
> reste pratique (éditeur, gestion des émulateurs, logs).

```sh
# JDK 21 (Temurin) + outils Android (SDK en ligne de commande) + (optionnel) l'IDE
brew install --cask temurin@21
brew install --cask android-commandlinetools
brew install --cask android-studio          # optionnel mais confortable

# Variables d'environnement (à mettre dans ~/.zshrc pour les rendre permanentes)
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin"

# Composants du SDK (accepter les licences puis installer)
sdkmanager --licenses                                              # accepter (yes)
sdkmanager "platform-tools"                                        # adb, fastboot
sdkmanager "platforms;android-36"                                  # API cible
sdkmanager "build-tools;36.1.0"
sdkmanager "emulator" "system-images;android-36;google_apis;arm64-v8a"

# Créer un émulateur nommé "test"
echo no | avdmanager create avd -n test -k "system-images;android-36;google_apis;arm64-v8a"
```

> **Apple Silicon** : prends bien les images `arm64-v8a` (rapides, virtualisation native).
> Sur un Mac Intel, remplace par `x86_64`.

### Le SDK doit être trouvable par le build

`./gradlew` localise le SDK via **`ANDROID_HOME`** (exporté ci-dessus) **ou** via un fichier
`local.properties` à la racine (non versionné). Si tu n'exportes pas `ANDROID_HOME` dans ton
shell, crée ce fichier une fois :

```sh
echo "sdk.dir=$(brew --prefix)/share/android-commandlinetools" > local.properties
```

---

## 2. Compiler & tester

Toujours avec `JAVA_HOME`/`ANDROID_HOME` exportés (cf. ci-dessus) :

```sh
cd /Users/ben/projects/finador-android

./gradlew testDebugUnitTest      # tests unitaires (JVM, sans appareil) — la couche moteur
./gradlew assembleDebug          # construit l'APK debug
./gradlew installDebug           # installe sur l'appareil/émulateur connecté
```

- **Première exécution** : longue (téléchargement de Gradle + dépendances). Ensuite c'est rapide.
- Le **build debug** est celui du quotidien. Un **build release** optimisé (R8, plus rapide et
  3× plus léger) existe aussi : `./gradlew installRelease` (signé avec la clé debug pour pouvoir
  l'installer en local ; à remplacer par une vraie clé avant toute distribution).

---

## 3. Lancer sur l'émulateur

L'émulateur doit tourner **avant** `installDebug` (sinon : *"No connected devices"*).
Dans **un terminal séparé** (il reste au premier plan) :

```sh
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
$ANDROID_HOME/emulator/emulator -avd test           # laisse tourner
# liste des AVD : $ANDROID_HOME/emulator/emulator -list-avds
```

Puis, une fois l'écran d'accueil Android affiché, dans le terminal du projet :

```sh
./gradlew installDebug
adb shell am start -n fin.android/.ui.MainActivity   # (re)lancer sans réinstaller
```

Arrêter l'émulateur : ferme la fenêtre, ou `adb emu kill`.
(Alternative GUI : Android Studio → *Device Manager* → ▶ — plus confortable.)

---

## 4. Lancer sur un vrai téléphone (ex. Galaxy S21)

1. Active le **mode développeur** : *Réglages → À propos → appuie 7× sur "Numéro de build"*.
2. Active **Débogage USB** : *Réglages → Options pour développeurs → Débogage USB*.
3. Branche le téléphone en USB, accepte l'autorisation qui s'affiche.
4. Vérifie qu'il est vu : `adb devices` (doit lister un appareil).
5. `./gradlew installDebug` puis ouvre l'app depuis le tiroir d'applications.

C'est aussi le meilleur moyen de juger le rendu réel (l'émulateur déforme un peu les
proportions par rapport à un écran haut comme le S21).

---

## 5. Astuces (dev peu habitué à Android)

- **`adb logcat`** = les logs du device. Pour ne voir que l'app :
  `adb logcat -s fin.android:V AndroidRuntime:E` (un crash apparaît en `AndroidRuntime: FATAL`).
- **Réinstaller proprement** : `adb uninstall fin.android` puis `installDebug` (efface aussi
  la config + les secrets stockés).
- **Captures d'écran** : `adb exec-out screencap -p > shot.png`.
- **`local.properties`, `build/`, `.gradle/`, `*.fin`** ne sont pas versionnés (cf. `.gitignore`).
- **Pas besoin d'installer Gradle** : `./gradlew` télécharge la version épinglée (8.13). Tu peux
  aussi `brew install gradle` si tu veux la commande `gradle` globale (pas nécessaire ici).
- **Versions** : compileSdk/targetSdk **36**, minSdk **26**, JDK **21**, AGP 8.13.2, Kotlin 2.2.20.
- **Onboarding (1er lancement)** : il te faut un **dépôt privé GitHub** + un **fine-grained PAT**
  (Settings → Developer settings → Personal access tokens → Fine-grained ; *Repository access* =
  ce seul dépôt ; *Permissions → Contents: Read and write*). Colle le token + une passphrase ;
  ensuite c'est le déverrouillage biométrique.

---

## 6. Structure & docs

```
app/src/main/kotlin/fin/android/
  crypto/  domain/  format/    # cœur pur Kotlin (Argon2id/HKDF/AES-GCM, modèle, lecture/écriture .fin)
  remote/  market/  valuation/ # sync GitHub, cotes multi-source, valorisation/perf/gains
  data/    ui/                 # DI + repository, écrans Compose
scripts/crossimpl.sh           # test de compatibilité bidirectionnelle avec le binaire Go
docs/superpowers/              # spec, plan, journal de décisions
```

Le format de fichier et le modèle de sync sont spécifiés côté finador :
`../finador/docs/FORMAT.md` et `../finador/docs/superpowers/specs/2026-06-13-github-remote-data-design.md`.
