# BoitierTube — compilation et validation Windows

## Portée de ce guide

Ce guide vise notamment **Windows 10 x64 avec Windows PowerShell 5.1** (fourni avec Windows 10).
PowerShell 7 n’est pas requis. Le script a été relu pour cette syntaxe, mais **ni le script ni
la compilation n’ont encore été exécutés sur Windows 10** : la compatibilité effective reste
à confirmer. La CI `windows-latest` utilise un environnement Windows Server et ne remplace pas
une recette Windows 10. Voir le [bilan d’audit](boitiertube-audit-windows10.md).

## Prérequis

- Windows 10 x64 (22H2 comme configuration de recette de référence), PowerShell 5.1 ou ultérieur,
  dépôt complet extrait dans un dossier local, avec `gradlew.bat` et `gradle/wrapper`.
  Utiliser PowerShell, pas l’invite `cmd.exe`, pour les commandes de ce guide.
- **JDK 21 complet** (par exemple Temurin), avec `java`, `javac` et `jpackage`.
  Définir `JAVA_HOME` vers le dossier du JDK, pas vers son sous-dossier `bin`.
- Accès réseau à la distribution Gradle et aux dépôts déclarés dans `settings.gradle.kts`
  (Gradle Plugin Portal, Google Maven, Maven Central, JitPack, Clojars), ainsi qu’aux dépendances
  transitives. Les versions actuellement épinglées n’ont pas pu être résolues dans la session
  de personnalisation : ne pas confondre préparation des fichiers et build validé.
- Pour les MSI/EXE avec JDK 21 : **WiX Toolset 3.x**, avec `candle.exe` et `light.exe` dans PATH.
- Pour les essais de lecture : VLC 64 bits. Pour les conversions MP3 et certains muxages : FFmpeg.
  VLC et FFmpeg ne sont pas nécessaires aux contrôles statiques des ressources.

## Préparer une session PowerShell sur Windows 10

Installer un **JDK 21 Windows x64 complet** compatible avec Windows 10, puis ouvrir une fenêtre
PowerShell normale (pas besoin d’administrateur pour compiler). Les commandes suivantes demandent
les vrais chemins de votre installation et de votre dépôt, et acceptent les espaces :

```powershell
$PSVersionTable.PSVersion
$env:JAVA_HOME = (Read-Host 'Chemin complet du dossier JDK 21, sans bin').Trim('"')
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
Set-Location -LiteralPath ((Read-Host 'Chemin complet du depot BoitierTube').Trim('"'))

Test-Path -LiteralPath .\gradlew.bat
Test-Path -LiteralPath .\gradle\wrapper\gradle-wrapper.jar
& (Join-Path $env:JAVA_HOME 'bin\java.exe') --version
& (Join-Path $env:JAVA_HOME 'bin\javac.exe') --version
& (Join-Path $env:JAVA_HOME 'bin\jpackage.exe') --version
```

Les deux `Test-Path` doivent retourner `True` et les trois outils doivent indiquer la version 21.
Ces variables ne sont modifiées que pour cette session PowerShell ; recommencer dans une nouvelle
fenêtre ou configurer les variables d’environnement utilisateur via Windows.

Pour les installateurs uniquement, installer WiX Toolset **3.x**, puis ajouter son dossier `bin`
réel au PATH de cette session. Vérifier avant de lancer `-Package` :

```powershell
Get-Command candle.exe, light.exe
```

Le script ne télécharge ni Java, ni WiX, ni VLC. Un JRE seul ne suffit pas. Compiler d’abord sans
`-Package` permet de distinguer un problème du projet d’un problème d’outillage WiX.

## Préparer les runtimes portables après consolidation

La branche centrale inclut maintenant le travail Arena de regroupement de VLC et FFmpeg.
Le build déclare `desktopApp/app-resources` comme racine des ressources natives : ce dossier
est ignoré par Git et les binaires ne sont pas inclus dans le dépôt.

- **Développement avec VLC/FFmpeg installés séparément** : créer au minimum le dossier vide
  avant `createDistributable`. La découverte des installations locales reste disponible.
  Un installateur construit ainsi ne doit pas être présenté comme incluant les runtimes.

```powershell
New-Item -ItemType Directory -Force -Path .\desktopApp\app-resources | Out-Null
```

- **Distribution avec runtimes inclus** : préparer les ressources avant de compiler/packager.
  Installer 7-Zip et rendre `7z` accessible (ou utiliser son chemin standard). Pour cette
  préparation réseau distincte, PowerShell 7 est recommandé ; la CI l’exécute avec `pwsh`.
  L’exécution locale de ce script de préparation sous Windows PowerShell 5.1 n’est pas validée.

```powershell
pwsh -File .\.github\scripts\prepare-windows-bundle.ps1
```

Le script télécharge FFmpeg et VLC depuis leurs sources configurées (GitHub/Gyan/VideoLAN),
prépare `desktopApp/app-resources/windows-x64/ffmpeg` et `vlc`, puis contrôle les exécutables.
Il refuse un dossier de sortie déjà présent et ne supprime plus de sortie existante.
Utiliser un checkout propre pour préparer un nouveau bundle, sans effacer l’ancien depuis ce script. Prévoir réseau, espace disque et temps
supplémentaires. Il ne fait pas partie de `verify-windows.ps1` et n’installe pas Java ou WiX.

La CI vérifie ensuite les fichiers et la configuration **BoitierTube.cfg** de la distribution,
puis exécute le smoke test VLC/FFmpeg réel. Hors CI, vérifier aussi le démarrage depuis le
raccourci installé. Avant toute redistribution publique, contrôler les licences et obligations
de redistribution des versions de VLC/FFmpeg téléchargées ; leur présence n’est pas une recette
juridique ou Windows 10.

## Commandes

Depuis la racine du dépôt, dans cette même fenêtre PowerShell :

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

### Dépannage Windows 10

| Symptôme | Contrôle / action |
| --- | --- |
| Exécution des scripts désactivée | Utiliser les commandes `gradlew.bat` ci-dessus ; ne pas contourner une politique d’entreprise. Le préflight peut être remplacé par les commandes de contrôle du JDK et des fichiers décrites plus haut. |
| `JAVA_HOME` invalide ou outil absent | Choisir la racine d’un JDK 21 complet x64, pas `bin`, pas un JRE ; vérifier les trois versions. |
| `gradlew.bat` introuvable | Revenir à la racine du dépôt ; ne pas lancer depuis `doc` ou `scripts`. |
| `candle.exe` / `light.exe` introuvables | Vérifier l’installation WiX 3.x et le PATH dans la même session ; Java seul ne permet pas le packaging MSI/EXE. |
| Échec de téléchargement, certificat, plugin ou dépendance introuvable | Conserver l’erreur exacte et vérifier réseau/proxy/certificats ainsi que la disponibilité des versions épinglées. Ne pas désactiver TLS ou changer les versions à l’aveugle. |
| Alias Python ouvrant le Store | Ce contrôle est facultatif pour la compilation ; installer Python 3 ou utiliser `py -3 scripts/check_desktop_identity.py` si le lanceur Python est installé. |
| Pas d’image/son après un build réussi | Vérifier VLC 64 bits et son chemin dans les paramètres ; vérifier séparément FFmpeg pour MP3/muxage. |

Les sorties attendues se trouvent dans `desktopApp\build\compose\binaries\main\` :
`app\` pour la distribution, `msi\` et `exe\` pour les installateurs.
Tester le raccourci et l’exécutable **installés**, pas seulement `gradlew run`, pour vérifier
l’icône de la barre des tâches. Pour le mode Système sur Windows 10 : Paramètres → Personnalisation
→ Couleurs → choisir le mode d’application clair/sombre (le mode Windows seul peut différer).

La CI Windows consolidée s’exécute aussi sur les pushes de `arena/1461b4cf-newpipe`.
Son résultat courant et les erreurs corrigées sont consignés dans le
[rapport de consolidation](boitiertube-consolidation.md). Les anciens bilans sans Java décrivent
les étapes précédentes, pas une validation de la version actuelle.

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


## Résultat de l’audit final et distribution sans installation

Voir [l’audit final](boitiertube-audit-final.md) pour le SHA réellement testé, les défauts corrigés,
les limites restantes et les liens des installateurs et du ZIP. La CI publie désormais aussi
`boitiertube-windows-portable` : extraire le ZIP **entier** puis lancer
`Start-BoitierTube-Portable.cmd` pour le profil isolé dans `data`. Le titre confirme le mode portable.
Ouvrir directement `BoitierTube.exe` garde le profil installé historique. Ne pas déplacer l’EXE seul.
Voir [les instructions portables](boitiertube-portable.md) et
[les derniers correctifs/artefacts](boitiertube-correctifs-avant-windows10.md). Tester sur un profil de recette avant toute
utilisation importante ; ne pas confondre le succès CI Windows Server avec une recette Windows 10.

Contrôles supplémentaires sans Java :

```powershell
python scripts/check_final_audit.py
```

Le script de préparation limite maintenant sa sortie au dossier généré du dépôt et refuse les
ancêtres liés. `-ValidatePathsOnly` permet de vérifier ce chemin sans télécharger, créer ou nettoyer
de fichiers ; il ne valide ni Java, ni WiX, ni les binaires.
