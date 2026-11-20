# BoitierTube — profils privés et mode portable

Extraire **tout le ZIP officiel d’artefact**, puis ouvrir `Start-BoitierTube-Portable.cmd`.
Ne pas lancer depuis l’archive. Le dossier extrait doit être accessible en écriture.

## Profils indépendants de NewPipe

- MSI/EXE installé (ou EXE lancé directement) : `%APPDATA%\BoitierTube` sous Windows.
- Lanceur portable : `<dossier extrait>\data\profiles\<identifiant de compte>`.

L’identifiant portable est une empreinte du domaine/machine Windows, du dossier personnel et du nom de compte. Deux
comptes distincts ne sélectionnent pas le même profil par défaut dans une même extraction. Cela
n’est ni un chiffrement ni une protection contre quelqu’un ayant accès aux fichiers. Le même
compte Windows partagé par deux personnes ne permet pas de les distinguer.

Chaque profil contient sa base, ses paramètres, ses journaux et ses temporaires. Aucun ancien
`NewPipeDesktop`, aucune préférence Java racine et aucun ancien profil portable plat `data` ne sont
lus ou migrés automatiquement. La correction de confidentialité démarre donc avec un profil neuf
par rapport aux anciennes versions utilisant ces emplacements. **Les anciens fichiers restent intacts.**

Une mise à jour/réinstallation conserve ensuite les seules données du nouveau profil BoitierTube
de ce compte : elle n’efface pas son historique à chaque lancement. Un autre compte Windows ou une
extraction neuve reçoit un profil neuf. Changer de compte ou de PC peut sélectionner un autre profil
portable ; aucun profil existant n’est recherché pour le recopier silencieusement.

## Ne pas partager son dossier déjà utilisé

Pour distribuer BoitierTube, partager le ZIP propre de CI ou l’installateur, **jamais son dossier
portable déjà utilisé**, une sauvegarde ou son dossier Documents. Même si un autre compte n’ouvre
pas automatiquement votre profil, les fichiers personnels copiés restent lisibles sur le support.
Le pipeline refuse les profils, bases, configurations personnelles et téléchargements reconnaissables
dans les packages générés, y compris les ressources JAR et les contenus des installateurs.

## Téléchargements

Les médias vont par défaut dans **Documents → BoitierTube → Vidéos / Audios**, hors du profil portable.
Documents est résolu par Windows, même s’il est redirigé. Les chemins personnalisés choisis dans le
nouveau profil restent prioritaires. Les anciens fichiers ne sont ni déplacés ni supprimés.

## Précautions

Le lanceur définit `BOITIERTUBE_PORTABLE_ROOT` uniquement pour son processus. Ne pas définir cette
variable globalement. Un EXE lancé directement utilise le profil installé, pas le profil portable.
Les paramètres des deux modes sont des fichiers `preferences.checked-json` distincts avec contrôle
SHA-256 et publication atomique ; ce contrôle détecte la corruption, pas l’identité du propriétaire.
Un profil inaccessible ou endommagé provoque une erreur, jamais un repli vers NewPipe.
Une seule instance peut ouvrir un même profil à la fois.

L’import/restauration reste une action **manuelle explicite** dans l’interface. Elle peut remplacer
les données du profil cible et réintroduire les chemins de la sauvegarde choisie. Vérifier les
chemins après une restauration ; aucune restauration n’est exécutée par le programme d’installation.
Les noms techniques `newpipe.db` et `newpipe.json` des sauvegardes restent compatibles ; ils ne
signifient pas qu’un ancien profil est consulté automatiquement.

Fermer l’application puis éjecter proprement une clé USB. Une coupure pendant une écriture ou une
restauration peut demander une récupération manuelle. Conserver les copies de secours signalées.
Le mode portable n’est pas un bac à sable : bibliothèques natives, caches OS et dialogues de fichiers
peuvent utiliser des emplacements extérieurs. La compatibilité des supports FAT/exFAT/NTFS et le
lancement interactif Windows 10 restent distincts des tests automatisés Windows Server.
