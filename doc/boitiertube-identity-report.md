# BoitierTube Desktop — rapport de personnalisation

## Statut actuel : code préparé, compilation et recette Windows non validées

Ce rapport remplace le bilan intermédiaire. Architecture : Kotlin/Compose Desktop, cible JVM,
packaging Windows. Base examinée : `91fb95939d2fb33689bb47a0b885ab36e228e86f` ; l’arbre était
propre avant la première intervention et avant cette seconde passe.

## Branche et commits

Tout le travail reste sur **`arena/1461b4cf-newpipe`**, branche imposée par la session.
La branche demandée initialement `boitierTube/identity-redesign` n’a pas été utilisée.
Aucune autre branche modifiée, aucun push, aucune fusion, aucune PR ou CI distante déclenchée.

Commits déjà créés :
- `84e3d34` — identité, thèmes et polices embarquées.
- `ce13eaa` — contrôles de ressources et tests de sélection des thèmes.
- `ee35dea` — rapport intermédiaire et documentation.
- `f0c6414` — palettes complètes, identité Desktop localisée, correctifs Kotlin et licences.
- `17452d2` — badges direct bleus contrastés et largeur de lecture À propos.
- Les fichiers de préparation Windows et le présent rapport font l’objet du commit
  `build(desktop): prepare Windows verification and document audit results`.

## Changements réalisés

### Couleurs et cohérence visuelle
- Palette générale bleue, surfaces blanches en clair et gris anthracite en sombre.
- Bleu ciel `#7DD3FC` en sombre et containers bleu ciel en clair ; bleu plus soutenu `#0276A8`
  pour les liens et accents clairs nécessitant un contraste lisible sur blanc.
- Palettes des cinq services reliées à la **palette complète** de l’application : plus de
  retombée sur les couleurs Material violettes derrière les containers bleus.
- Badge « direct » passé du rôle erreur à primary/onPrimary : bleu et texte contrasté,
  sans blanc forcé sur un accent clair en sombre. Les erreurs restent orange, pas rouges.
- Aucun ancien littéral d’accent rouge/brun recherché ne subsiste dans les thèmes actifs.
  Les surfaces vidéo noires, scrims translucides et images de contenus restent intentionnels.

### Thèmes
- Sélecteur existant : Clair, Sombre, Système ; pas de deuxième système de thèmes.
- Ancienne valeur Noir interprétée comme Sombre ; valeurs inconnues importées suivent le système.
- Suppression de l’ancienne branche nocturne devenue redondante ; les clés historiques restent
  lisibles sans influencer la nouvelle palette sombre.
- Suivi Windows toujours délégué à `isSystemInDarkTheme()` de Compose. Son bon fonctionnement
  à chaud sous Windows n’est pas prouvé par les contrôles statiques.
- Imports Kotlin `getValue`/`setValue` manquants rétablis ; état mémorisé attaché à l’instance
  Settings et listener correctement rattaché. Tests ajoutés pour la matrice de sélection,
  les palettes des services et les préférences fournies (non exécutés faute de Java).

### Textes, À propos et licences
- BoitierTube dans fenêtre, tray, package installable, vendor, menu Démarrer et BuildConfig.
- Ressources **Desktop dédiées FR/EN** pour présentation, licence, cookies et import des
  abonnements. Les autres langues utilisent leur repli anglais Desktop plutôt que les anciennes
  traductions Android. Les traductions historiques sont conservées, mais ne sont plus utilisées
  par ces écrans. Il ne s’agit pas d’une traduction complète vers toutes les langues.
- Cartes À propos localisées : dépôt du fork et crédit explicite NewPipe original.
- Présentation française demandée et équivalent anglais ; largeur de paragraphe plafonnée
  à 760 dp pour la lisibilité sur grand écran.
- Le dialogue GPL porte le nom BoitierTube ; crédits NewPipe et licences des dépendances conservés.
- Source Sans 3 Regular/Semibold embarquées, tailles/interlignes Material augmentés de 2,5 %.
  Licence SIL OFL avec copyright Adobe désormais **consultable dans l’onglet Licences**.
  Le dialogue recharge le texte quand on change de licence.
- Contrôle Android SAF non fonctionnel masqué sur Desktop, en conservant le choix des dossiers
  locaux et les fonctionnalités de téléchargement.

### Logo et Windows
- Logo original B + Play central conservé, SVG et PNG 512 px, ICO 9 résolutions (16 à 256 px).
- Aperçus 16, 24, 32 et 48 px ouverts et inspectés : B et Play discernables, très compacts à 16 px.
  Cela ne remplace pas un contrôle du rendu réel dans le shell Windows à plusieurs DPI.
- Deux anciens fichiers Desktop NewPipe rouges supprimés ; aucun appel actif à ces fichiers.
- Licence GPL déclarée dans le packaging.
- Nouveau script `scripts/verify-windows.ps1` : préflight JDK 21 complet et ressources,
  builds/tests/distribution, MSI+EXE optionnels avec contrôle de WiX 3.x et propagation des erreurs.
- CI Windows existante adaptée : contrôles Python, build/tests, MSI+EXE et artefact BoitierTube.
  Aucun changement des versions Gradle/dépendances sans preuve de nécessité.

## Compatibilité conservée

Pas de réécriture des moteurs VLC, extraction, recherche, SQLite ou téléchargements.
Les identifiants de packages techniques, chemins `%APPDATA%/NewPipeDesktop` et
`Downloads/NewPipe`, noms internes des sauvegardes et UUID MSI restent inchangés pour
préserver les données et formats compatibles. Les mentions NewPipe restantes dans les crédits,
versions de formats, fichiers et messages de compatibilité ne sont pas des noms de l’application.
Les dialogues AWT/Swing et menus natifs peuvent suivre le style Windows et non le thème Compose.

## Vérifications réellement exécutées

- `python scripts/check_desktop_identity.py` : **11 tests réussis**. XML, textes FR/EN,
  repli Desktop sans branding Android, références des ressources Kotlin avec défaut existant,
  polices/licence, absence des anciennes icônes et anciens accents recherchés, badges direct,
  structure ICO/PNG et contrastes des rôles principaux >= 4,5:1.
- Pillow : **deux TTF chargées**, noms internes Source Sans 3 Regular/Semibold vérifiés ;
  **9 images ICO décodées** avec dimensions correctes ; PNG 16/24/32/48 décodés.
- Inspection visuelle des aperçus du logo à 16/24/32/48 px.
- YAML CI lu avec PyYAML ; présence des contrôles et packaging MSI+EXE confirmée.
  Cela ne prouve pas le succès des actions GitHub.
- `git diff --check` : réussi.
- Nouvelle tentative `bash gradlew :desktopApp:build :shared:jvmTest --console=plain` :
  **échec avant lancement de Gradle**, Java absent / JAVA_HOME non défini.

## Limites et non réalisé

- **Aucune compilation Kotlin ni aucun test JVM/Compose exécuté**, aucun MSI/EXE généré.
- PowerShell absent : le nouveau script n’a pas été exécuté sous Windows.
- Versions de dépendances et plugins non résolues/validées ; les hôtes Gradle/Maven nécessaires
  ne sont pas autorisés dans cet environnement. Préparation ne signifie pas compilation garantie.
- Pas de recette Windows des thèmes, DPI, taskbar, notifications, polices dans le binaire,
  installation/mise à niveau ou débordements sur chaque écran.
- Pas de test fonctionnel lecture/recherche/téléchargements/restauration. L’absence de changement
  des moteurs n’est pas une preuve d’absence de régression.
- Traductions Desktop autres que FR/EN à produire si souhaitées ; repli anglais désormais prévu.
- Les styles droits Regular/Semibold sont fournis ; autres graisses rapprochées du Semibold et
  caractères non couverts traités par les fallbacks de Compose.

Voir [le guide Windows](boitiertube-windows-build.md) pour prérequis, commandes et matrice de recette.
**La mission ne doit pas être déclarée complètement terminée avant compilation et recette Windows.**

## Fichiers modifiés, ajoutés ou supprimés par rapport à la base

- `.github/workflows/ci.yml`
- `assets/boitiertube/icon-16.png`
- `assets/boitiertube/icon-24.png`
- `assets/boitiertube/icon-32.png`
- `assets/boitiertube/icon-48.png`
- `assets/boitiertube/logo.svg`
- `desktopApp/build.gradle.kts`
- `desktopApp/src/main/kotlin/net/newpipe/app/Main.kt`
- `desktopApp/src/main/resources/boitiertube.ico`
- `desktopApp/src/main/resources/boitiertube.png`
- `desktopApp/src/main/resources/newpipe.ico`
- `desktopApp/src/main/resources/newpipe.png`
- `doc/README.md`
- `doc/boitiertube-identity-report.md`
- `doc/boitiertube-windows-build.md`
- `scripts/check_desktop_identity.py`
- `scripts/verify-windows.ps1`
- `shared/build.gradle.kts`
- `shared/src/commonMain/composeResources/drawable/ic_foreground.xml`
- `shared/src/commonMain/composeResources/drawable/ic_newpipe_logo.png`
- `shared/src/commonMain/composeResources/files/LICENSES/OFL-1.1.txt`
- `shared/src/commonMain/composeResources/font/source_sans_3_regular.ttf`
- `shared/src/commonMain/composeResources/font/source_sans_3_semibold.ttf`
- `shared/src/commonMain/composeResources/values-fr/desktop_identity.xml`
- `shared/src/commonMain/composeResources/values-fr/strings.xml`
- `shared/src/commonMain/composeResources/values/desktop_identity.xml`
- `shared/src/commonMain/composeResources/values/strings.xml`
- `shared/src/commonMain/kotlin/net/newpipe/app/App.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/screen/about/AboutPage.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/screen/about/AboutScreen.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/screen/about/LicenseDialog.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/screen/about/LicensePage.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/screen/desktop/DesktopCommonCards.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/screen/desktop/SubscriptionsScreen.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/screen/settings/AppearanceSettingsScreen.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/screen/settings/DownloadSettingsScreen.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/screen/settings/HistorySettingsScreen.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/theme/Color.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/theme/ServiceTheme.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/theme/Theme.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/theme/Typography.kt`
- `shared/src/commonTest/kotlin/net/newpipe/app/screen/settings/AppearanceSettingsScreenTest.kt`
- `shared/src/commonTest/kotlin/net/newpipe/app/screen/settings/DownloadSettingsScreenTest.kt`
- `shared/src/commonTest/kotlin/net/newpipe/app/theme/BoitierThemeTest.kt`
- `shared/src/jvmMain/kotlin/net/newpipe/app/platform/JVMBackupRestoreActions.kt`
- `shared/src/jvmMain/resources/licenses/source-sans-3-OFL.txt`

## Provenance et outils

Polices officielles : `https://codeload.github.com/adobe-fonts/source-sans/zip/refs/heads/release`.
TTF non modifiées, licence officielle conservée. Pillow/resvg et PyYAML ont été utilisés dans
un environnement virtuel local pour les ressources/contrôles, sans dépendance d’exécution ajoutée.

- SHA-256 `source_sans_3_regular.ttf` : `4644c81b86ec9caaa76b634889968ed3c4f4f52f054855933acc7c2b21e53b0f`
- SHA-256 `source_sans_3_semibold.ttf` : `a3f4f8dcf343a8f24dc61951de93f3ba1558b15cd250ba24af8a40e957081b7d`
