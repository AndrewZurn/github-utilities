# github-utilities
Uses the Github API to provide some useful utilities, such as analyzing pull requests.

## To Build
Run `./gradlew packageDistribution`.
This will build a jar for the application and move it to the `dist/lib/` directory.

## To Run
Once you've built the app, you can execute it from the `dist/` directory.

Example - Print Merged Stats
```
github-utilities/dist> ./github-utilities --analyze merged --pr-limit 10 --repo-name <your-repo-name>
```

Example - Print in JSON
Example - Print Merged Stats
```
github-utilities/dist> ./github-utilities --analyze open --pr-limit 5 --repo-name <your-repo-name> --output json
```
