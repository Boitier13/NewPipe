# Consolidation BoitierTube — sept branches

## Branche centrale et périmètre

Branche de travail et de publication : **`arena/1461b4cf-newpipe`**. Son nom reste celui
imposé à cette session ; aucune branche de remplacement n’a été créée.
Les sept pointes distantes ont été relevées avant modification. L’inventaire JSON associé
contient les SHA complets, bases communes, listes de commits, fichiers/numstats et les
21 comparaisons deux à deux : [inventaire](boitiertube-branch-inventory.json).
L’historique complet a été téléchargé, pas une simple comparaison de clones superficiels.

| Branche source | Pointe initiale | Commits absents de la centrale initiale | Commits exclusifs face aux six autres pointes | Traitement |
| --- | --- | ---: | ---: | --- |
| `dev` | `91fb95939d` | 0 | 0 | Déjà ancêtre, conserve notamment les intégrations PR 1 et 2 |
| `arena/4a0ce2e9-newpipe` | `ee3097b754` | 0 | 0 | Déjà intégrée via dev : muxage MP4 long, audio multiformat, import CSV et correctifs/tests |
| `arena/7a8836d3-newpipe` | `580d77a1bf` | 0 | 0 | Déjà intégrée via dev : logs, flux tamponné, progression muxage, file et vitesse |
| `boitierTube/identity-redesign` | `3eec74a7c5` | 8 | 8 | Fusion explicite `e8960ca31a` ; arbitrage identité décrit ci-dessous |
| `boitiertube/french-localization-fixes` | `2612512ef0` | 28 | 28 | Fusion `8fbfff981d` ; traductions, accessibilité et écrans préservés |
| `arena/ae9cabd8-newpipe` | `26f1912bc5` | 4 | 4 | Fusion `0b305b3821` ; runtimes portables et diagnostics CI |
| `arena/1461b4cf-newpipe` | `3488bbc768` | — | 2 | Centrale existante : identité avancée et deux commits de sauvegarde exclusifs |

Attention : les comptes « exclusifs » excluent tous les commits atteignables depuis une autre
pointe. Un zéro ne signifie donc pas que la branche n’avait apporté aucun travail.
Les 28 commits français incluent eux-mêmes une partie de l’historique Arena d’identité.

## Résolution sémantique, pas seulement résolution Git

### Identité initiale / identité Arena

Quatre conflits : Gradle desktop, catalogues anglais et français et palette `Color.kt`.
La centrale conserve sa palette bleue accessible, ses couleurs sémantiques, ses rôles contrastés,
la police Source Sans 3 et ses licences, les ressources d’icônes, son titre/fenêtre/tray et la copie
À propos avancée. L’ancienne branche proposait également BoitierTube mais une palette plus ancienne
et une copie de dons moins aboutie. On ne réintroduit pas ces versions au détriment du travail Arena.
La description de paquet « Lecteur video libre pour ordinateur » est intégrée ; noms MSI/EXE,
menu et fournisseur restent BoitierTube. Les identifiants techniques NewPipe, références amont,
licences et UUID de mise à niveau ne sont pas renommés aveuglément.

### Francisation / restauration sécurisée

La fusion textuelle française réussissait, mais les catalogues réunis contenaient **23 clés dupliquées**
entre `strings.xml` et `backup_safety.xml`. Elles sont maintenant définies une seule fois par locale.
Les traductions françaises sont conservées, sauf arbitrages documentés nécessaires pour :
- expliquer une restauration base **et paramètres** indivisible, après validation ;
- ne pas proposer un ancien format de paramètres vulnérable ;
- parler d’un sélecteur de fichiers desktop plutôt que recommander un gestionnaire Android ;
- proposer les détails d’une erreur au lieu de promettre implicitement un signalement.

Les deux variantes et la valeur finale de chaque clé figurent dans
[les décisions de résolution](boitiertube-integration-resolutions.json).
Les **373 clés** du `strings.xml` de la branche française sont toujours présentes.
Le `desktop_identity.xml` français est identique à celui de cette branche.
Les huit écrans desktop conservent leurs traductions/accessibilités introduites par la branche.
Dix-huit ressources utilisées par ces écrans n’avaient aucune définition dans la locale par défaut :
un catalogue de repli anglais a été ajouté, sans supprimer leurs traductions françaises.
Les classes de sauvegarde et leurs tests sont préservés octet pour octet depuis la centrale initiale.

### Runtimes Windows / identité et CI

La découverte des VLC/FFmpeg portables, les réglages de lecture, le contrôleur VLC, les tests
WindowsAppPaths et le smoke test natif sont préservés octet pour octet depuis la branche Arena.
Le script de préparation des binaires et `appResourcesRootDir` sont intégrés ; les binaires restent
hors Git. Le conflit CI est résolu en conservant les étapes séparées et leurs annotations d’erreur,
plus les contrôles d’identité. Les chemins `NewPipe.cfg` et `app/NewPipe` deviennent
`BoitierTube.cfg` et `app/BoitierTube` dans les vérifications d’artefacts. La CI construit aussi
les EXE en plus des MSI et vérifie que l’upload ne passe pas silencieusement sans installateur.
Un déclencheur push explicite sur la centrale a été ajouté.

Le [guide Windows](boitiertube-windows-build.md) décrit maintenant la préparation des ressources
portables. Un build avec VLC installé localement n’équivaut pas à un installateur autonome.

## Vérifications reproductibles

```sh
python scripts/check_desktop_identity.py
python scripts/check_branch_consolidation.py
git diff --check
```

- **13 contrôles statiques réussis**, y compris unicité des clés XML et présence des valeurs par défaut.
- Les **7 pointes gelées sont ancêtres de HEAD**. Vérification de contenu complémentaire pour les
  traductions françaises, les sauvegardes, l’identité et les runtimes ; pas seulement une preuve d’ascendance.
- Analyse syntaxique tree-sitter des **13 fichiers Kotlin modifiés par les fusions** : sans erreur.
  Ce contrôle ne remplace pas une compilation Kotlin.
- Le build local Gradle a réellement été tenté mais ne démarre pas : ni Java ni JAVA_HOME dans
  l’environnement de session. Aucune installation Windows n’a été exécutée dans ce bac à sable.

## Compilation et CI Windows

Le dispatch manuel Actions est refusé par GitHub avec HTTP 403 (droits de l’intégration),
mais le push déclenche effectivement la CI. Il ne s’agit pas d’un échec d’authentification git.

1. [Première exécution consolidée](https://github.com/Boitier13/NewPipe/actions/runs/44743149706),
   commit `203d9cb3fe` : JDK 21, contrôles d’identité et préparation VLC/FFmpeg réussis ; compilation
   Kotlin échouée sur six délégués Compose de l’écran Apparence. L’import
   `androidx.compose.runtime.getValue` était déjà absent dans la centrale avant consolidation.
2. Correction minimale publiée dans `9a2bbb691c`, avec contrôle de non-régression de l’import.
3. [Validation après correction](https://github.com/Boitier13/NewPipe/actions/runs/44743615184),
   commit **`9a2bbb691ce8bd40ed176e0b2841506fdb2bd779`** : **SUCCÈS**, job terminé en **18 min 05 s**.
   Toutes les étapes suivantes ont réussi : tests JVM partagés (y compris la classe de 19 tests de
   sauvegarde incluse dans cette suite), contrôles desktop, build/distribution desktop, vérification
   de la configuration BoitierTube et des ressources, exécution réelle FFmpeg/initialisation VLC,
   génération MSI **et** EXE, contrôle du MSI et publication de l’artefact.

   Artefact Actions : **`boitiertube-windows`**, ID `14632388560`, **415 083 969 octets**
   (archive regroupant les installateurs, pas taille individuelle d’un MSI).
   [Téléchargement sur GitHub Actions](https://github.com/Boitier13/NewPipe/actions/runs/44743615184/artifacts/14632388560).
   L’artefact est conservé par Actions selon la rétention du dépôt, pas ajouté à Git.
   Avertissement non bloquant de fin de job : sauvegarde du cache Gradle par `tar.exe` échouée ;
   le résultat global et toutes les étapes de test/build/package/upload sont néanmoins `success`.

Le seul commit ultérieur prévu ne modifie que la documentation et l’inventaire ; le code et
le workflow publiés correspondent au SHA testé ci-dessus. Aucun résultat individuel de test XML
n’a été téléchargé dans cette session : le constat d’exécution repose sur le succès de la tâche
`:shared:jvmTest`, sans filtre, dont la classe de sauvegarde fait partie.

Une CI Windows Server n’est pas une recette interactive Windows 10 : lecture, téléchargement,
import/export réels, installation/mise à niveau et apparence restent à valider manuellement.
Le rapport de sauvegarde précédent décrit des tests alors inexécutés ; seul le résultat du run
courant peut attester leur exécution après consolidation.

## Suppression de branches : garde-fou

**Aucune branche distante supprimée.** L’intégration de contenu est démontrée et la CI Windows
a réussi. La recette interactive reste distincte. `dev` est en outre encore la branche par défaut
au moment de l’inventaire ; sa suppression exigerait une décision sur la branche par défaut.
Les règles de cette session limitent la publication à la branche courante : ne pas pousser de
suppression sur les autres refs depuis cette session. Le nom technique de la centrale n’empêche
pas le nom produit BoitierTube.

Avant un éventuel nettoyage autorisé hors de cette restriction :
1. Vérifier le succès CI du SHA central publié et conserver les artefacts utiles.
2. Réactualiser `git ls-remote --heads origin` : une source peut avoir reçu de nouveaux commits.
3. Si une pointe diffère de l’inventaire, refaire comparaison, intégration, tests et preuves.
4. Confirmer la branche par défaut et les PR/automatisations qui utilisent les anciennes branches.
5. Supprimer uniquement les refs réellement redondantes, jamais la centrale ; conserver les preuves.

Dernier contrôle `git ls-remote --heads origin` : les six pointes sources sont inchangées par
rapport à l’inventaire ; seule la centrale a progressé. Les sept branches sont encore présentes.

Les anciennes pointes restent récupérables depuis l’historique de fusion publié et leurs SHA dans
l’inventaire ; conserver les branches jusqu’à validation ne perd ni ne duplique leur code dans l’application.
