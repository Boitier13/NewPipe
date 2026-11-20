# BoitierTube — sécurisation des sauvegardes et restaurations

## Statut et branche

**Implémentation réalisée, validation fonctionnelle et compilation encore bloquées.**
Travail sur `arena/1461b4cf-newpipe`, à la demande de poursuivre sur la branche de cette session.
La branche `boitiertube/french-localization-fixes` et son commit `2612512…` n’ont pas été modifiés
ni intégrés. Le travail d’identité déjà publié jusqu’à `b317af5` a été conservé à l’identique.
Aucune fusion avec upstream ni création de PR.

## Analyse du fonctionnement initial

- SQLite Bundled, connexion JVM unique, journal WAL, opérations de base protégées par un Mutex.
- Schéma Desktop basé sur NewPipe v9 ; `ensureOpenLocked` ouvre et prépare le schéma.
- Les préférences sont injectées via Settings, stockées par Java Preferences à la racine
  utilisateur ; ce backend stocke les valeurs sous forme de chaînes et fournit des getters typés.
- L’ancien export fermait la connexion, écrivait directement le fichier de destination et ne
  réouvrait la base qu’après succès. Son checkpoint WAL pouvait échouer sans être signalé.
- L’ancien import fermait la base, supprimait les fichiers auxiliaires et écrivait directement
  dans `newpipe.db` pendant l’extraction. Les erreurs de paramètres étaient ignorées et la
  vérification de présence des fichiers survenait après la substitution.

`WindowsAppPaths.kt` a été relu et reste inchangé : noms, base et chemins historiques préservés.

## Protections ajoutées

### Avant de modifier la base actuelle

1. Vérifier existence, lisibilité, taille non nulle et limite du fichier source.
2. Ouvrir le ZIP via son répertoire central ; exiger exactement deux entrées uniques à la racine :
   **`newpipe.db` et `newpipe.json`**. Refuser autres entrées, dossiers, doublons et chemins arbitraires.
3. Extraire en flux vers des noms fixes dans un dossier privé temporaire créé sur le volume
   de la base. Les noms fournis par l’archive ne deviennent jamais des chemins de destination.
4. Borner la taille déclarée ET les octets effectivement lus ; vérifier longueurs finales et CRC.
5. Décoder strictement l’UTF-8, analyser le JSON et refuser les clés dupliquées, valeurs nulles,
   objets/collections ou types non pris en charge. Aucun paramètre n’est ignoré silencieusement.
6. Vérifier SQLite (`integrity_check`, `foreign_key_check`), version compatible, présence des
   tables essentielles, préparation du schéma et colonnes nécessaires aux lectures/écritures.
   Ces opérations se déroulent sur la copie isolée, pas sur la base active.
7. Vérifier l’espace disque disponible. Produire une copie SQLite cohérente de la base actuelle
   par `VACUUM INTO`, incluant les écritures WAL validées, puis une copie typée de tous les
   paramètres actuels. Forcer ces copies sur disque avant la fermeture.

### Substitution et retour arrière

- Tout le parcours est protégé par le Mutex de la base. L’opération n’est pas interrompue
  par l’annulation d’une coroutine au milieu de la substitution.
- Après validation et création des secours seulement : fermer la connexion et déplacer les
  fichiers originaux, y compris les éventuels WAL/SHM/journal restants, dans le dossier de secours.
- Installer la base préparée par déplacement sur le même volume ; appliquer tous les paramètres,
  vérifier leur présence et leurs valeurs, puis réouvrir et rafraîchir la base.
- Java Preferences reste observable et expose désormais `flush()` : une erreur de persistance
  est remontée, et non considérée comme un succès parce qu’un getter voit encore le cache mémoire.
- En cas d’échec : fermer la nouvelle connexion, rétablir l’instantané précédent et tous les
  paramètres avec leurs types, puis tenter la réouverture. Si la copie de retour échoue
  (par exemple disque plein), tenter un retour par renommage des originaux archivés.
- Les listes observables ne sont publiées qu’après lecture réussie de toutes les listes.
- Les secours ne sont supprimés qu’après succès complet ou retour arrière complet.
  Un nettoyage temporaire impossible est journalisé sans transformer une opération déjà
  validée en faux échec.

### Export

- Ne ferme plus la base : un échec de ZIP n’empêche donc pas son accès ultérieur.
- Instantané SQLite cohérent et paramètres sérialisés sans remplacer les valeurs non textuelles
  illisibles par des chaînes vides.
- ZIP écrit dans un fichier `.partial` unique dans le dossier de destination, fermé et forcé
  sur disque avant publication du nom choisi.
- Publication par lien dur atomique sans remplacement lorsque disponible ; sinon déplacement
  sans `REPLACE_EXISTING`. Aucun usage d’`ATOMIC_MOVE` pour contourner le refus d’écrasement.
- Refus d’un nom déjà occupé, y compris s’il apparaît entre le début et la publication.
- Un échec d’écriture supprime le temporaire autant que possible ; aucun ZIP incomplet n’est
  publié comme une sauvegarde terminée.

### Interface française

- Erreurs de sécurité et erreurs génériques compréhensibles en français ; détails techniques
  réservés aux logs, pas exposés via les exceptions SQLite/ZIP brutes.
- Messages et libellés manquants de l’écran de sauvegarde complétés en français.
- La restauration est indivisible : la confirmation française annonce le remplacement de la
  base ET des paramètres. Suppression du faux second choix de paramètres déjà appliqués.
- Notification de réussite uniquement après validation complète de la restauration.

## Limites de format et compatibilité

- ZIP compressé : 512 Mio maximum ; base extraite : 512 Mio maximum ; JSON : 4 Mio maximum.
- Marge libre minimale : 32 Mio, en plus des estimations de fichiers de travail et de secours.
  Un contrôle préalable ne réserve pas physiquement l’espace : les erreurs d’écriture restent traitées.
- Jusqu’à 4096 paramètres, clés de 1 à 80 caractères, valeurs de 8192 caractères maximum.
- Format JSON public conservé : objet plat de valeurs primitives (chaînes, booléens, nombres finis).
  Sur le backend Java Preferences, les chaînes exportées conservent leur interprétation par
  les getters typés. Les copies internes de secours conservent aussi les types exacts.
- Noms internes `newpipe.db` / `newpipe.json` inchangés. Versions SQLite futures supérieures à 9,
  anciens paramètres Java sérialisés, collections et ZIP contenant d’autres fichiers sont
  explicitement refusés, sans import partiel. La compatibilité avec toutes les générations
  de sauvegardes Android/NewPipe n’est pas revendiquée ni testée sur des archives réelles ici.

## Tests ajoutés : 19 méthodes Kotlin, NON EXÉCUTÉES

`DesktopBackupStoreTest.kt` emploie SQLite Bundled réel dans des dossiers de test dédiés,
avec une connexion WAL et des points d’injection déterministes. Il prévoit :

- Restauration réussie, paramètres et base remplacés ensemble.
- ZIP corrompu, vide, tronqué, CRC invalide ; source inexistante.
- Base ou paramètres absents ; base vide/corrompue ou SQLite valide mais sans schéma attendu.
- JSON invalide, UTF-8 invalide, type incorrect, clé dupliquée.
- Entrées supplémentaires/dupliquées et chemins dangereux.
- Limites compressées/extraites et espace disque insuffisant.
- Échecs injectés pendant extraction, création du secours, fermeture, substitution,
  application des paramètres, réouverture et après réouverture.
- Échec d’un backend Settings pendant une écriture, import partiel suivi de restauration typée,
  erreur de flush, et backend durablement en panne avec conservation des secours.
- Repli par renommage si la copie de retour échoue.
- Export incluant les données WAL, noms compatibles, conservation de la connexion active.
- Aller-retour avec Java Preferences dans un nœud de test isolé.
- Export interrompu/impossible, destination existante ou créée concurremment, nettoyage du partiel.
- Messages français ne divulguant pas l’erreur technique brute.

Les cas récupérables vérifient la ligne initiale, la ligne WAL et les paramètres initiaux, et
exécutent une requête/écriture après l’échec. Le cas de backend de paramètres durablement en
panne vérifie les lignes SQLite et les copies de secours : il ne prétend pas pouvoir réécrire
les paramètres dans un stockage qui refuse toutes les écritures.

Les tests de substitution/espace disque utilisent notamment des injections de panne ; ils ne
simulent pas physiquement un disque matériel plein ni les verrous d’un autre processus Windows.
La préparation spécifique du schéma complet dans JVMDesktopDatabase nécessite encore une recette
avec de vraies archives BoitierTube/NewPipe ; le coordinateur est testé avec une petite base témoin.

## Vérifications réellement exécutées

- `bash gradlew :shared:jvmTest :desktopApp:build :desktopApp:createDistributable --console=plain` :
  **échec avant le démarrage de Gradle**, Java/JAVA_HOME absent. Aucune compilation Kotlin,
  aucun des 19 tests fonctionnels ci-dessus exécuté, aucune distribution produite.
- `python scripts/check_desktop_identity.py` : **11 tests statiques de ressources réussis**.
  Ce ne sont pas des tests de restauration.
- XML de tous les catalogues : parsing et absence de clés dupliquées vérifiés.
- Six fichiers Kotlin (coordinateur, base, actions, SettingsModule, tests, écran) analysés avec
  tree-sitter-kotlin : aucune erreur syntaxique détectée. Ce contrôle ne valide ni types, ni
  dépendances, ni génération Koin et ne remplace pas le compilateur.
- `git diff --check` : réussi.

## Réserves importantes avant livraison

Il n’est pas honnête de garantir « aucune perte possible sous toute erreur » : panne matérielle,
arrêt brutal du processus, erreurs fatales JVM, plusieurs instances sur la même base ou refus
persistant du stockage peuvent empêcher même un retour arrière. Les copies sont conservées
quand le retour ne se termine pas et le message donne leur emplacement. **La reprise automatique
au redémarrage après coupure n’est pas implémentée** ; l’application ne dispose pas d’une
transaction atomique commune à SQLite, Java Preferences et au système de fichiers.

En cas de retour arrière incomplet : ne pas recommencer une importation, ne pas supprimer le
dossier `.boitiertube-backup-*` indiqué, arrêter l’application et conserver une copie de ce
répertoire. `previous.db` est l’instantané initial ; `previous-settings.json` est une copie typée
de secours, **pas directement le fichier public newpipe.json**. Prévoir une récupération assistée
une fois les droits/le disque/le backend réparés, sans manipuler une base encore ouverte.

Aucune garantie de disponibilité immédiate n’est possible si le système refuse aussi de réouvrir
la base. Les échecs de réouverture sont tentés et signalés ; ils ne sont pas masqués.

## Commandes à exécuter avec JDK 21 et les dépendances disponibles

```powershell
.\gradlew.bat :shared:jvmTest --tests "net.newpipe.app.platform.DesktopBackupStoreTest" --stacktrace
.\gradlew.bat :shared:jvmTest :desktopApp:build :desktopApp:createDistributable --stacktrace
```

Recette manuelle : exporter des données de test, vérifier la restauration de l’historique,
des abonnements et des paramètres, puis essayer plusieurs archives corrompues et dossiers
non accessibles. Après chaque échec, relire/écrire des données et redémarrer l’application.
Ne jamais utiliser les seules données réelles de l’utilisateur pour cette première recette.

## Fichiers de cette intervention

- `shared/src/jvmMain/kotlin/net/newpipe/app/platform/DesktopBackupStore.kt` (nouveau)
- `shared/src/jvmMain/kotlin/net/newpipe/app/platform/JVMDesktopDatabase.kt`
- `shared/src/jvmMain/kotlin/net/newpipe/app/platform/JVMBackupRestoreActions.kt`
- `shared/src/jvmMain/kotlin/net/newpipe/app/di/settings/SettingsModule.kt`
- `shared/src/jvmTest/kotlin/net/newpipe/app/platform/DesktopBackupStoreTest.kt` (nouveau)
- `shared/src/commonMain/kotlin/net/newpipe/app/screen/settings/BackupRestoreSettingsScreen.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/screen/settings/model/BackupRestoreMessage.kt`
- `shared/src/commonMain/kotlin/net/newpipe/app/viewmodel/settings/BackupRestoreSettingsViewModel.kt`
- `shared/src/commonMain/composeResources/values/backup_safety.xml` (nouveau)
- `shared/src/commonMain/composeResources/values-fr/backup_safety.xml` (nouveau)
- `doc/boitiertube-backup-safety.md` (ce rapport)

Commit principal : `a7ff9fa` ; un second commit finalise le nettoyage non bloquant et ce rapport.
Branche : https://github.com/Boitier13/NewPipe/tree/arena/1461b4cf-newpipe
