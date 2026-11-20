# BoitierTube Desktop — rapport de personnalisation

## Statut : implémentation partielle, validation Windows requise

Le dépôt a été examiné avant modification : Compose Multiplatform/Kotlin, cible JVM Desktop,
packaging Windows via Compose. L’arbre Git était propre et la branche de session partait de
`91fb95939d2fb33689bb47a0b885ab36e228e86f`.

### Branche et opérations Git

**Le travail est resté exclusivement sur `arena/1461b4cf-newpipe`.** La session impose cette
branche ; la demande de travailler sur `boitierTube/identity-redesign` n’a donc **pas** été
réalisée. Aucune autre branche modifiée, aucune fusion, aucune PR, aucun push.

Commits d’implémentation :
- `84e3d34` — feat(desktop): introduce BoitierTube identity, blue themes and bundled typography
- `ce13eaa` — test(desktop): check branding resources, contrast and theme choices
- Le présent rapport et le README sont enregistrés dans un troisième commit documentaire.

## Changements réalisés

- Palette partagée remplacée : accents bleus, blanc en clair, gris anthracite en sombre,
  surfaces et textes neutres. Les erreurs utilisent un orange contrasté, pas un accent rouge.
  Le noir des surfaces vidéo et des scrims est conservé pour sa fonction, pas comme fond de thème.
- Les couleurs de navigation des services sont harmonisées en bleu et suivent le thème choisi
  dans l’application, y compris quand celui-ci diffère du système.
- Le sélecteur existant expose Clair, Sombre, Système. Les valeurs historiques « noir » restent
  lisibles et pointent vers le gris sombre. L’ancien sous-sélecteur nocturne est retiré de l’UI ;
  les clés et callbacks restent compatibles. Le suivi système existant repose toujours sur
  `isSystemInDarkTheme()` de Compose : aucun second mécanisme de thème ajouté.
- Source Sans 3 Regular et Semibold embarquées dans les ressources Compose, avec correspondances
  Normal/Medium/SemiBold/Bold et augmentation de 2,5 % des tailles et interlignes Material standards.
  La licence SIL OFL est embarquée dans les ressources JVM. Aucune dépendance d’exécution ajoutée.
- Logo original dessiné en SVG : B blanc central entier, Play bleu en réserve dans le B,
  sur fond bleu. PNG 512 px, ICO avec 9 tailles (16 à 256 px), aperçus 16/24/32/48 px.
  Utilisé par la fenêtre, le tray, le packaging, la barre latérale et À propos.
  Le rendu 32 px a été ouvert et inspecté visuellement : silhouette B et Play discernables.
  L’inspection visuelle de toutes les tailles dans le shell Windows reste à faire.
- Nom BoitierTube dans la fenêtre, le tray, BuildConfig, le nom du paquet installable et le
  groupe du menu Démarrer. Le vendor est BoitierTube, sans attribuer ce fork à NewPipe e.V.
- À propos : description Desktop française demandée (et version anglaise), suppression des
  cartes Android/FAQ/politique amont présentées comme celles du fork, liens vers le dépôt du fork
  et vers le projet original avec attribution explicite. Onglet des licences conservé.
- Titres de licence et texte applicatif FR/EN adaptés sans retirer les copyrights ou la GPL.
  Dialogues d’import/export renommés ; références aux formats de sauvegarde NewPipe conservées.
- README adapté, tests de ressources ajoutés et test Compose du sélecteur mis à jour.

## Compatibilité préservée intentionnellement

Pas de changement des moteurs VLC, extraction, recherche, navigation, téléchargement,
SQLite, historique et abonnements. Les packages `net.newpipe`, identifiants Gradle techniques,
chemins `%APPDATA%/NewPipeDesktop`, dossiers de téléchargement historiques, noms internes des
sauvegardes et UUID de mise à niveau MSI restent inchangés pour éviter de perdre les données
ou de rompre la compatibilité. Le comportement réel d’une mise à niveau MSI reste à tester.
Les anciens fichiers d’icône Desktop non référencés sont conservés, mais ne sont plus utilisés
par l’entrée de fenêtre et la configuration de packaging modifiées.

## Tests réellement exécutés

1. `bash gradlew :desktopApp:build :shared:jvmTest --console=plain` : **échec avant lancement
   de Gradle**, `JAVA_HOME is not set and no 'java' command could be found in your PATH`.
   Aucune compilation Kotlin, aucun test JVM/Compose exécuté.
2. `python scripts/check_desktop_identity.py` : **5 tests réussis**. Vérifie parsing de tous
   les XML Compose, textes À propos FR/EN, présence/signature des deux TTF et licence OFL,
   structure ICO et tailles, identité du PNG partagé/Desktop, contrastes texte/fond des rôles
   principaux (au moins 4,5:1), fond clair blanc et fond sombre non noir.
   Une première exécution avait échoué sur une différence d’encodage des PNG identiques en
   apparence ; les deux usages partagent désormais exactement le même fichier binaire.
3. `git diff --check` : réussi.
4. Image 32 px inspectée dans le visualiseur. Les autres tailles ont été générées et leur
   présence contrôlée, sans validation visuelle exhaustive.

Les tests Compose ajoutés pour Clair/Sombre/Système/ancien Noir et le sélecteur sont du code
**non exécuté**, et non une preuve de validation des thèmes à l’exécution.

## Difficultés et limites / non réalisé

- Java/JDK absent. Les hôtes Gradle/Maven nécessaires ne font pas partie des hôtes réseau
  autorisés de cette session ; aucune installation de toolchain ni résolution des dépendances
  n’a été prétendue. Aucun MSI/EXE produit.
- Pas de session Windows : pas de validation du thème Windows en direct, menus AWT natifs,
  taskbar, DPI, sélection d’installation, police réellement chargée dans le binaire, rendu de
  toutes les pages/dialogues, débordements et notifications.
- Les traductions autres que français et anglais ne sont pas adaptées : elles peuvent encore
  présenter des textes NewPipe/Android dans À propos. Le renommage multilingue exhaustif reste
  donc incomplet. Les mentions légales et références de formats ne doivent pas être remplacées
  aveuglément.
- Les nouvelles cartes À propos sont en français même avec une autre langue ; leur localisation
  et une validation linguistique générale restent à faire.
- Les opérations lecture, recherche, navigation, téléchargement, restauration et reprise n’ont
  pas été testées fonctionnellement ici. Le code des moteurs est conservé, mais cela ne suffit
  pas à affirmer l’absence de régression.
- La police ne comprend que les styles droits Normal/Semibold : les autres graisses sont
  rapprochées du Semibold ; les écritures non couvertes utilisent le fallback de Compose.

## Validation à effectuer sur Windows avant livraison

Avec JDK 21 et accès aux dépôts de dépendances :

```powershell
.\gradlew.bat :desktopApp:build :shared:jvmTest
.\gradlew.bat :desktopApp:run
.\gradlew.bat :desktopApp:packageMsi :desktopApp:packageExe
```

Tester les trois choix de thème, changement clair/sombre dans Windows, redémarrage et
persistance, chaque page et dialogue, recherche/lecture/VLC/plein écran/audio de fond,
téléchargements MP4/MP3/audio, historique/abonnements/import-export, tray et notifications,
installation/mise à niveau, polices hors installation système et icônes 16–256 px à plusieurs DPI.
**Ne pas considérer la mission terminée avant ces validations et les adaptations restantes.**

## Fichiers modifiés ou ajoutés (par rapport à la base)

- `assets/boitiertube/icon-16.png`
- `assets/boitiertube/icon-24.png`
- `assets/boitiertube/icon-32.png`
- `assets/boitiertube/icon-48.png`
- `assets/boitiertube/logo.svg`
- `desktopApp/build.gradle.kts`
- `desktopApp/src/main/kotlin/net/newpipe/app/Main.kt`
- `desktopApp/src/main/resources/boitiertube.ico`
- `desktopApp/src/main/resources/boitiertube.png`
- `doc/README.md`
- `doc/boitiertube-identity-report.md`
- `scripts/check_desktop_identity.py`
- `shared/build.gradle.kts`
- `shared/src/commonMain/composeResources/drawable/ic_foreground.xml`
- `shared/src/commonMain/composeResources/drawable/ic_newpipe_logo.png`
- `shared/src/commonMain/composeResources/font/source_sans_3_regular.ttf`
- `shared/src/commonMain/composeResources/font/source_sans_3_semibold.ttf`
- `shared/src/commonMain/composeResources/values-fr/strings.xml`
- `shared/src/commonMain/composeResources/values/strings.xml`
- `shared/src/commonMain/kotlin/net/newpipe/app/App.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/screen/about/AboutPage.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/screen/settings/AppearanceSettingsScreen.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/theme/Color.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/theme/ServiceTheme.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/theme/Theme.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/theme/Typography.kt`
- `shared/src/commonTest/kotlin/net/newpipe/app/screen/settings/AppearanceSettingsScreenTest.kt`
- `shared/src/commonTest/kotlin/net/newpipe/app/theme/BoitierThemeTest.kt`
- `shared/src/jvmMain/kotlin/net/newpipe/app/platform/JVMBackupRestoreActions.kt`
- `shared/src/jvmMain/resources/licenses/source-sans-3-OFL.txt`

## Provenance des polices

Archive officielle `https://codeload.github.com/adobe-fonts/source-sans/zip/refs/heads/release`.
Deux fichiers TTF extraits sans modification et licence officielle conservée. Les outils Python
Pillow/resvg ont servi uniquement à préparer les icônes dans un environnement virtuel local ;
ils ne sont ni ajoutés aux dépendances de l’application, ni versionnés.

- SHA-256 `source_sans_3_regular.ttf` : `4644c81b86ec9caaa76b634889968ed3c4f4f52f054855933acc7c2b21e53b0f`
- SHA-256 `source_sans_3_semibold.ttf` : `a3f4f8dcf343a8f24dc61951de93f3ba1558b15cd250ba24af8a40e957081b7d`
