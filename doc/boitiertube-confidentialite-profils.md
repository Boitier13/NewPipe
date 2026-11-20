# Audit de confidentialité — isolation de BoitierTube et NewPipe

## Cause identifiée avant modification

La révision `be68f766b` avait gardé trois identifiants de l’ancien desktop NewPipe :

1. `WindowsAppPaths.appDataDir()` retournait `%APPDATA%\NewPipeDesktop` en installé.
   `JVMDesktopDatabase.ensureOpenLocked()` ouvrait donc directement sa base `newpipe.db`.
   Les écrans affichaient les données de cette base réelle : ce n’était pas une simple question
   de filtrage d’interface, et aucune fusion ni import n’était nécessaire pour voir l’historique.
2. `provideSettings()` utilisait `Preferences.userRoot()` sans sous-espace propre à l’application.
   Les paramètres, chemins personnels, options d’historique et éventuels cookies enregistrés par
   l’ancien logiciel pouvaient être relus. Le portable avait déjà un fichier de paramètres distinct.
3. Le MSI/EXE gardait le même `upgradeUuid` que le package NewPipe de la base `91fb959`.
   C’était aussi une collision d’identité Windows Installer, indépendante du fichier de base.

La collision de base et de préférences est une cause établie dans le code. Elle explique l’apparition
locale de données sur un PC ayant utilisé l’ancien logiciel. Elle ne prouve pas que l’historique du
développeur était inclus dans le ZIP : ce deuxième risque exige l’inspection des packages générés.
Aucun accès au PC utilisateur n’a été effectué pour lire son historique privé.

## Corrections

- Installé Windows : `%APPDATA%\BoitierTube`, propre au compte Windows courant.
  macOS/Linux : même séparation de marque dans Application Support / `.local/share`.
- Paramètres installés : fichier `preferences.checked-json` dans ce profil, avec le mécanisme
  transactionnel/observable déjà éprouvé en portable. Aucune lecture de la racine Java Preferences.
- Portable : `<extraction>\data\profiles\<empreinte du compte>`. L’empreinte dépend du dossier
  personnel canonique et du nom de compte. Les comptes distincts ne sélectionnent pas le même
  profil par défaut. Ce mécanisme n’est ni une authentification forte ni du chiffrement.
- Aucun ancien `NewPipeDesktop`, `data/newpipe.db`, `data/preferences.checked-json` ou profil
  installé n’est recherché, importé, déplacé ou fusionné automatiquement par le nouveau portable.
- Nouvel `upgradeUuid` BoitierTube indépendant de NewPipe :
  `2e637c78-4bbc-4c83-8db5-0ec02e83a5e7`. Aucun script de désinstallation/migration NewPipe ajouté.
- Le nom interne `newpipe.db` et les noms des entrées ZIP de sauvegarde ne sont pas renommés
  aveuglément : ils restent des identifiants techniques de schéma/compatibilité dans un profil isolé.
  Aucun crédit ou élément de licence n’est retiré.

Les historiques, abonnements et réglages de l’ancienne version deviennent donc invisibles parce que
**leur stockage n’est plus ouvert**, et non parce qu’ils sont masqués dans l’interface. Ils restent
physiquement intacts. La correction ne recopie pas non plus l’ancien BoitierTube qui partageait cet
espace, puisqu’on ne peut pas distinguer sûrement l’origine de chaque entrée.

## Mise à jour, réinstallation et partage

Après cette séparation initiale, une mise à jour ou réinstallation dans le même compte conserve
les données du **nouveau profil BoitierTube**. Il ne faut pas effacer l’historique légitime à chaque
lancement. Un autre compte Windows ou une extraction portable neuve démarre vide. Deux personnes
utilisant exactement le même compte Windows et le même profil ne peuvent pas être distinguées
par l’application : utiliser des comptes Windows distincts pour cette séparation.

Le nouveau code de mise à niveau ne retire pas automatiquement les installations utilisant
l’ancien identifiant partagé. Une ancienne installation BoitierTube peut nécessiter une désinstallation
manuelle via Windows avant remplacement, après fermeture et conservation des données. Ne pas
confondre avec NewPipe et ne supprimer aucun dossier de données pour y parvenir. Le scénario exact
de coexistence/remplacement d’une ancienne installation réelle reste à vérifier sous Windows 10.

Toujours distribuer les **artefacts propres de CI**, pas son dossier portable déjà utilisé, ses
sauvegardes ou ses Documents. Un dossier contenant `data` transporte physiquement ces données même
si un autre compte ne les ouvre pas automatiquement. Le hash du compte ne rend pas les fichiers
secrets. Les médias restent par défaut sous Documents\BoitierTube\Vidéos / Audios du compte courant.
Les chemins personnalisés sont conservés seulement dans le profil BoitierTube où ils ont été choisis.
Une redirection Documents vers un partage réseau partagé reste un choix Windows extérieur à l’application.

L’import de sauvegarde/abonnements depuis l’interface reste possible **sur action explicite**.
Il n’est jamais lancé au démarrage ni par l’installateur. L’audit n’a pas trouvé d’auto-updater de
profils dans l’entrée desktop ; aucune nouvelle fonction de mise à jour n’est ajoutée.

## Vérifications automatisées ajoutées

### Profils et données

- Test déterministe de deux comptes dans une même extraction : paramètres séparés, anciens fichiers
  plats inchangés, profil stable pour un même compte.
- Quatre exécutions de `PrivacyProfileSmokeTest` dans des JVM Windows distinctes, avec APPDATA
  temporaire : installé neuf, installé rouvert, portable neuf, portable rouvert.
- Présence volontaire d’une ancienne base SQLite factice avec vidéo/recherche/abonnement privés
  marqués `PRIVATE_NEWPIPE`, préférences Java racines factices et ancien profil portable plat.
  Le code de test prépare ces canaris ; le code applicatif ne lit aucun de ces emplacements.
- Au premier démarrage : **toutes les clés de paramètres absentes**, réglages par défaut, cookies
  vides, abonnements vides, historiques visionnage/recherche et suggestions vides, file vide.
- Création de données BoitierTube propres : vidéo vue, recherche, abonnement, paramètre ; nouvelle
  JVM vérifiant leur persistance et la distinction installé/portable.
- Téléchargement réel HTTP local vers Documents via le contrôleur ; comparaison des octets écrits.
  Ce sont des octets de test, pas une validation de téléchargement YouTube ou de décodage vidéo.
- Comparaison SHA-256 de l’ancienne base et du profil plat avant/après, et conservation des valeurs
  Java factices. Aucune donnée privée réelle de développeur n’est utilisée comme fixture.

### Packages

- Audit structurel des ressources sources, du ZIP portable, des JAR imbriqués et des contenus MSI.
- Extraction en lecture des ressources de l’EXE jpackage pour récupérer son propre MSI embarqué.
- Extraction administrative séparée du MSI autonome et de celui de l’EXE ; contrôle du payload
  et de l’absence d’anciennes préférences dans la table Registry.
- Refus des profils, bases SQLite, journaux, cookies, sauvegardes/configurations personnelles et
  médias téléchargés reconnaissables. Tests synthétiques de refus, y compris dans un JAR imbriqué.
- Contrôle de provenance du ZIP par SHA du commit dans `VERSION-PORTABLE.txt` conservé.

Cette inspection structurelle n’est pas un détecteur universel de secrets arbitrairement renommés.
Elle complète le constat qu’aucun profil/donnée personnelle n’est une entrée de packaging prévue.
Les informations légales et configurations nécessaires aux runtimes restent incluses.

## Limites de validation à respecter dans le bilan

Le sandbox local n’a pas Java ni Windows : Gradle local a été tenté mais ne démarre pas. La CI
Windows exécute les tests/compilations. Un profil neuf sur Windows Server CI n’est pas une installation
interactive Windows 10 vierge. L’extraction administrative MSI n’installe pas le produit et ne prouve
pas le premier double-clic ni une désinstallation/réinstallation réelle. Les essais de lecture
YouTube, formats et lecture VLC restent ceux des tests physiques antérieurs, pas de nouvelles mesures.
Le bilan de conversation donne les runs, succès/échecs réels et artefacts du commit livré.

Fichiers applicatifs modifiés : `WindowsAppPaths.kt`, `SettingsModule.kt`, `PortableProfile.kt`,
`desktopApp/build.gradle.kts`. Tests/contrôles : `ProfilePrivacyTest.kt`, `PrivacyProfileSmokeTest.kt`,
`PortableModeSmokeTest.kt`, `audit_distribution_privacy.py`, `test_distribution_privacy.py`,
`verify-windows-package-privacy.ps1`, `check_final_audit.py`, `.github/workflows/ci.yml`.
Documentation : ce rapport, le guide quotidien et `boitiertube-portable.md`.

Aucune publication officielle, suppression de branche ou modification de `dev` n’est prévue.
