# BoitierTube — audit de gel avant compilation Windows 10

## Conclusion et périmètre

**Audit statique effectué ; application non validée à l’exécution.** Java et PowerShell sont
absents de l’environnement Linux de cette session. Aucun lancement de l’application,
compilation, test JVM/Compose, installation MSI/EXE ni test Windows 10 n’est donc attesté.

Aucun changement du code applicatif, des ressources, des versions ou du script de build
n’a été réalisé pendant cette passe. Seuls ce bilan et le guide Windows ont été écrits/modifiés.
Le développement visuel est gelé en attendant un build et une recette réels.

Branche : `arena/1461b4cf-newpipe`. Base de comparaison :
`91fb95939d2fb33689bb47a0b885ab36e228e86f`. Arbre initial propre. Aucun push, changement de
branche, fusion, PR ou déclenchement de CI distante.

## 1. Marque et écrans principaux

Recherche dans les sources applicatives et dans les ressources référencées, toutes traductions
confondues. Aucun nom NewPipe employé comme nom d’application n’a été trouvé dans les écrans
principaux examinés. Cela ne certifie pas l’absence absolue de texte provenant des dépendances,
de métadonnées générées, de données utilisateur restaurées ou d’erreurs affichées à l’exécution.

| Écran / élément | Vérification statique | Reste à exécuter |
| --- | --- | --- |
| Accueil / exploration | `DesktopHomeSearchScreen.kt`, cartes, navigation ; pas de littéral de marque NewPipe ni de couleur RGB directe dans cet écran | Chargement des tendances, cartes, miniatures, menus, défilement et DPI |
| Recherche | Même écran, ressources, suggestions, actions de navigation ; pas de nouvel appel métier dans le diff | Saisie, suggestions, résultats, filtres et navigation |
| Lecteur vidéo | `VideoDetailScreen.kt` inchangé ; noir et blanc réservés aux surfaces/commandes vidéo ; badge direct partagé en primary/onPrimary | VLC, lecture, audio, erreurs, résolution, plein écran, reprise et arrière-plan |
| Paramètres | Libellés de catégories et sélecteur relus ; cookies et présentation utilisent les textes Desktop ; seuls les éléments de présentation prévus ont changé | Chaque catégorie, dialogues, persistance et retour de navigation |
| Téléchargements | `DownloadsScreen.kt` inchangé ; choix de dossiers conservé ; ancien contrôle SAF Android désactivé masqué sur Desktop | MP4/MP3/audio, pause/reprise, erreurs, ouverture des fichiers et dossiers |
| Menus et tray | Titre BoitierTube, nouvelle icône, mêmes callbacks lecture/pause/arrêt/quitter | Clics réels, minimisation, restauration, arrêt et notifications |
| À propos / licences | Description et titre Desktop, crédit amont distinct, GPL et OFL accessibles par le code existant | Chargement des licences, liens, rendu FR/EN et repli anglais |

### Mentions NewPipe conservées volontairement

Les seules clés de ressources **référencées** dont le texte contient NewPipe ou Android sont :
- `desktop_upstream_title` et `desktop_upstream_description` : crédit explicite du projet original ;
- `desktop_subscription_import_description` : fichier au format NewPipe `subscriptions.json` ;
- `import_settings_vulnerable_format` : avertissement technique relatif aux versions NewPipe 0.27.0.

Les autres occurrences directes examinées concernent des URL GitHub, un aperçu de dialogue
non présenté par l’application, les packages techniques, logs, fichiers historiques et chemins
de données. Les mentions légales et noms des dépendances ne doivent pas être supprimés.

### Limites linguistiques encore présentes

Les menus natifs du tray sont codés en français et certains titres natifs d’import/export
restent en anglais (`Export Subscriptions JSON`, `Import Subscriptions (CSV / JSON)`).
Ce n’est pas un retour de la marque NewPipe, mais l’interface n’est pas entièrement homogène
linguistiquement. Aucune modification supplémentaire n’a été faite sans compilation possible.
Les nouvelles présentations Desktop non traduites utilisent volontairement l’anglais de repli.

## 2. Thèmes Clair, Sombre et Système

- Sélection relue : Clair force la palette claire ; Sombre et ancien Noir forcent la palette
  anthracite ; Système et valeurs inconnues suivent le résultat Compose du thème système.
- Toutes les palettes des services pointent vers les palettes complètes de l’application.
- Fond clair blanc ; fond sombre non noir ; contrôle automatisé des contrastes principaux
  supérieur ou égal à 4,5:1. Les badges direct utilisent les deux rôles contrastés du thème.
- Aucun ancien littéral rouge/brun recherché dans les thèmes ; les couleurs des vecteurs
  inspectés se limitent aux masques noir/blanc et au bleu du logo.
- Tests Kotlin présents mais **non exécutés**. Ni recomposition à chaud, ni notification de
  changement Windows, ni rendu de tous les contrôles ne sont validés.
- Menus et sélecteurs AWT/Swing peuvent suivre le style du système plutôt que Compose.

Recette Windows 10 requise : essayer Clair puis Sombre indépendamment du mode Windows,
puis Système en changeant le **mode d’application** dans Paramètres → Personnalisation → Couleurs.
Vérifier à chaud et après redémarrage ; tester les zooms 100/125/150/200 %.

## 3. Icônes et polices

- `Main.kt` utilise `/boitiertube.png` pour fenêtre et tray ; le PNG existe et se décode en 512 × 512.
- La navigation utilise le même PNG binaire dans les ressources Compose.
- `desktopApp/build.gradle.kts` déclare BoitierTube comme package/menu et référence
  `boitiertube.ico` pour Windows. Anciennes icônes Desktop retirées, aucune référence active.
- ICO chargé et **neuf images décodées** : 16, 20, 24, 32, 40, 48, 64, 128 et 256 px.
- Les deux TTF ont été chargées avec Pillow ; noms internes confirmés : Source Sans 3
  Regular et Semibold. Cela ne prouve pas leur chargement par Compose dans la distribution.

**Non vérifié :** icône effective du processus installé, barre des tâches épinglée, cache Windows,
raccourcis, écran installateur, icône de l’EXE, shell à différents DPI et polices sans installation
système. La présence d’un ICO valide dans la configuration ne prouve pas ces résultats.

## 4. Préservation des fonctions héritées

Comparaison Git réussie (`git diff --exit-code` sans différences) avec la base pour :
- tous les viewmodels et toute la navigation partagée ;
- tous les utilitaires Java Desktop de flux et muxage ;
- `DesktopStreamMuxer.kt`, `VlcMediaPlayerController.kt`, `JVMYouTubeBackend.kt` ;
- `JVMDownloadController.kt`, `JVMDesktopDatabase.kt`, `WindowsAppPaths.kt`.

Le diff de `Main.kt` ne change que ressources et textes, pas les callbacks de contrôle.
`JVMBackupRestoreActions.kt` ne change que deux titres de dialogues. Les modifications de
cartes et souscriptions sont des couleurs ou ressources de texte ; pas de changement métier.
Les changements de préférences de thèmes et de présentation de stockage restent les points
à couvrir avec les tests UI adaptés.

**Ce constat réduit le périmètre de risque mais ne prouve pas l’absence de régression.**
Les tests fonctionnels lecture/recherche/navigation/téléchargement/stockage/restauration
restent obligatoires sur un build réel, avec sauvegarde des données existantes avant mise à niveau.

## 5. Guide Windows 10

Le guide `boitiertube-windows-build.md` a été complété sans toucher au script :
- cible Windows 10 x64 et PowerShell 5.1, pas d’obligation d’installer PowerShell 7 ;
- configuration de session JAVA_HOME/PATH avec chemins réels saisis par l’utilisateur ;
- contrôle de java/javac/jpackage 21 et de la racine du dépôt ;
- distinction build simple / packaging WiX 3.x ;
- alternative `gradlew.bat` si la politique PowerShell bloque les scripts ;
- diagnostic des échecs classiques, sorties attendues et contrôle du mode d’application Windows.

Le script n’utilise pas de syntaxe spécifique à PowerShell 7, vérifie les prérequis et les codes
de sortie Gradle, et restaure le dossier courant. **Relecture seulement, pas de validation
PowerShell 5.1 exécutée.** La disponibilité des versions Gradle/plugins/dépendances épinglées
reste non vérifiée. `windows-latest` n’est pas une preuve de support Windows 10.

## 6. Contrôles réellement exécutés pendant cette passe

| Contrôle | Résultat |
| --- | --- |
| `python scripts/check_desktop_identity.py` | 11 tests réussis |
| Recherche des références NewPipe/Android dans les ressources utilisées | Mentions compatibles/légales identifiées ci-dessus, pas de présentation Android référencée trouvée |
| Inspection des écrans et diffs indiqués | Réalisée sur le code, pas sur une application lancée |
| Comparaison Git des moteurs / navigation / viewmodels | Aucune différence sur les chemins listés |
| Chargement des deux TTF et décodage ICO/PNG avec Pillow | Réussi |
| `git diff --check` | Réussi |
| Vérification des outils disponibles | Ni `java` ni `pwsh` trouvés |
| Compilation, tests Kotlin, PowerShell et recette Windows 10 | Non exécutables ici ; aucune nouvelle tentative Gradle inutile après constat de l’absence de Java |

## Décision

**Pas de nouveau changement applicatif majeur avant compilation.** La prochaine étape est
l’exécution du guide sur Windows 10 x64 avec JDK 21, puis la matrice de tests manuels.
Ne pas marquer le produit comme validé ou prêt à livrer à ce stade.
