# DoubleOuRien

Plugin Paper (Minecraft 26.2, Java 25) : quitte ou double façon « upgrader ».
Tu mises des objets, tu choisis une cible plus précieuse, la roulette décide.

## Commandes
- `/dor` : ouvre l'interface (alias `/doubleourien`)
- `/dor value <ITEM>` : affiche la valeur d'un objet
- `/dor setvalue <ITEM> <valeur>` : change un prix (admin)
- `/dor reload` : recharge `config.yml` et `values.yml` (admin)
- `/dor give` : donne l'objet (admin). Recette : 4 lingots d'or + 4 diamants + 1 enclume

Permissions : `doubleourien.use` (tous), `doubleourien.admin` (op).

## Compiler avec GitHub
1. Crée un dépôt GitHub et pousse ce dossier dessus.
2. L'onglet **Actions** lance la compilation automatiquement.
3. Télécharge `DoubleOuRien` dans les **Artifacts** du run, puis mets le jar dans `plugins/`.
4. Pour une Release : `git tag v1.0.0 && git push --tags`.

## Compiler en local
`mvn package` (JDK 25) → `target/DoubleOuRien.jar`
