# BoitierTube — compilation et validation Windows

## Prérequis

- Windows 64 bits, PowerShell, dépôt complet avec `gradlew.bat` et `gradle/wrapper`.
- **JDK 21 complet** (par exemple Temurin), avec `java`, `javac` et `jpackage`.
  Définir `JAVA_HOME` vers le dossier du JDK, pas vers son sous-dossier `bin`.
- Accès réseau à la distribution Gradle et aux dépôts déclarés dans `settings.gradle.kts`
  (Gradle Plugin Portal, Google Maven, Maven Central, JitPack, Clojars), ainsi qu’aux dépendances
  transitives. Les versions actuellement épinglées n’ont pas pu être résolues dans la session
  de personnalisation : ne pas confondre préparation des fichiers et build validé.
- Pour les MSI/EXE avec JDK 21 : **WiX Toolset 3.x**, avec `candle.exe` et `light.exe` dans PATH.
- Pour les essais de lecture : VLC 64 bits. Pour les conversions MP3 et certains muxages : FFmpeg.
  VLC et FFmpeg ne sont pas nécessaires aux contrôles statiques des ressources.

## Commandes

Depuis la racine du dépôt :

```powershell
# Facultatif : tests de ressources sans Java, avec Python 3
python scripts/check_desktop_identity.py

# Contrôle du JDK et de la présence des ressources, sans compiler
.\scripts\verify-windows.ps1 -PreflightOnly

# Tests JVM, build Desktop et distribution avec runtime
.\scripts\verify-windows.ps1

# Même vérification, puis génération MSI et EXE (WiX nécessaire)
.\scripts\verify-windows.ps1 -Package

# Démarrage pour validation manuelle
.\gradlew.bat :desktopApp:run
```

Si une politique PowerShell interdit les scripts, consulter la politique de l’organisation
ou utiliser les commandes Gradle directement. Ne pas désactiver globalement la politique :

```powershell
.\gradlew.bat :shared:jvmTest :desktopApp:build :desktopApp:createDistributable --stacktrace
.\gradlew.bat :desktopApp:packageMsi :desktopApp:packageExe --stacktrace
```

Le script retourne une erreur si un prérequis manque ou si une commande Gradle échoue ;
il ne remplace pas une installation Java et ne masque pas les erreurs du compilateur.
Il n’a pas pu être exécuté dans l’environnement Linux sans PowerShell/JDK de cette session.

La CI Windows existante a été adaptée pour contrôler les ressources, construire et tester,
puis générer et conserver les deux installateurs sous `boitiertube-windows`.
Aucune exécution distante n’a été déclenchée ni déclarée réussie.

## Ressources à vérifier dans la distribution produite

- Fenêtre, tray, menu Démarrer, taskbar, fichiers et dialogues d’installation : **BoitierTube**.
- Icône `boitiertube.ico` : 16, 20, 24, 32, 40, 48, 64, 128, 256 px.
- PNG 512 px, logo de la navigation et de la page À propos identiques.
- Source Sans 3 Regular et Semibold disponibles sans installation des polices dans Windows.
- Licence GPL fournie au packaging, crédits NewPipe et licences tierces conservés.
- Source Sans 3 accessible depuis l’onglet Licences avec le copyright Adobe et la SIL OFL.
- Chemins historiques `%APPDATA%\NewPipeDesktop` et dossiers Downloads/NewPipe conservés
  intentionnellement : vérifier les données existantes avant/après une mise à niveau.

## Matrice de validation manuelle indispensable

| Contrôle | Attendu |
| --- | --- |
| Clair, Windows clair/sombre | Fond blanc, accents bleus, sélection persistante |
| Sombre, Windows clair/sombre | Fond gris foncé non noir, texte lisible |
| Système, changement Windows à chaud | Suit Windows sans modifier le choix utilisateur |
| Ancien réglage Noir | S’affiche comme Sombre, sans écran noir absolu |
| Recherche et suggestions | Saisie, suggestions, résultats et navigation inchangés |
| Lecture | VLC, son, plein écran, volume, résolution, reprise |
| Audio en arrière-plan | Mini-lecteur et commandes tray fonctionnels |
| Téléchargements | MP4, MP3, audio, progression, erreurs et ouverture des fichiers |
| Historique/abonnements | Persistance, import/export et formats NewPipe compatibles |
| À propos FR/EN | Texte Desktop, noms BoitierTube, liens pertinents, crédits conservés |
| Autre langue | Présentation Desktop anglaise de repli, pas de présentation Android |
| Licences | GPL et OFL s’ouvrent, changement de licence recharge le bon texte |
| Affichage | Petites fenêtres, zoom Windows 100/125/150/200 %, pas de texte coupé |
| Installation/mise à niveau | MSI/EXE, raccourcis, désinstallation et conservation des données |

Les menus et dialogues natifs AWT/Swing peuvent suivre le style de Windows plutôt que les
couleurs Compose. Les images des vidéos et les logos des services tiers ne sont pas recolorés.
Les surfaces vidéo noires et les voiles noirs translucides restent intentionnels.
