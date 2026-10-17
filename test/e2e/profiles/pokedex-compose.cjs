// pokedex-compose: small (14 modules), checked on every change. See ../suite.cjs for the fields.
module.exports = {
  name: 'pokedex-compose',
  minutes: { sync: 10, ready: 10 },
  // Must analyze without errors.
  clean: [
    'feature/home/src/main/kotlin/com/skydoves/pokedex/compose/feature/home/HomeViewModel.kt', // Kotlin 2.3 explicit backing fields
    'core/data/src/main/kotlin/com/skydoves/pokedex/compose/core/data/repository/details/DetailsRepositoryImpl.kt',
    'core/model/src/main/kotlin/com/skydoves/pokedex/compose/core/model/Pokemon.kt',
    'feature/home/src/main/kotlin/com/skydoves/pokedex/compose/feature/home/PokedexHome.kt',
    'core/network/src/main/kotlin/com/skydoves/pokedex/compose/core/network/di/NetworkModule.kt',
    'core/datastore/src/main/kotlin/com/skydoves/pokedex/compose/core/datastore/UserPreferencesSerializer.kt',
  ],
  // Completion: `type` is typed one character at a time at `find` + `offset` (after `insert`), as in the editor.
  completions: [
    {
      name: 'member across modules',
      file: 'core/data/src/main/kotlin/com/skydoves/pokedex/compose/core/data/repository/details/DetailsRepositoryImpl.kt',
      find: 'pokemonInfoDao.getPokemonInfo',
      offset: 'pokemonInfoDao.'.length,
      expect: ['getPokemonInfo', 'insertPokemonInfo'],
    },
  ],
  definitions: [
    { name: 'class in another module', file: 'feature/home/src/main/kotlin/com/skydoves/pokedex/compose/feature/home/HomeViewModel.kt', find: ': HomeRepository,', offset: 4, expectFile: 'HomeRepository.kt' },
  ],
  hovers: [],
  references: [
    { name: 'Pokemon', file: 'core/model/src/main/kotlin/com/skydoves/pokedex/compose/core/model/Pokemon.kt', find: 'data class Pokemon(', offset: 'data class '.length, min: 11, module: 'feature/home' },
  ],
  codeLens: [{ name: 'Pokemon.kt', file: 'core/model/src/main/kotlin/com/skydoves/pokedex/compose/core/model/Pokemon.kt', resolve: 100 }],
  // Optimize Imports and Reformat Code on a tidy file, with badly spaced code added in the editor.
  formatting: 'core/model/src/main/kotlin/com/skydoves/pokedex/compose/core/model/Pokemon.kt',
};
