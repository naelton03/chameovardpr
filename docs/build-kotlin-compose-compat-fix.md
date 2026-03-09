# Fix definitivo de build Android: compatibilidade Kotlin x Compose Compiler

## Sintoma no CI
Falha em `:expo-modules-core:compileReleaseKotlin` com erro de compatibilidade:
- Compose Compiler `1.5.15` requer Kotlin `1.9.25`
- build estava usando Kotlin `1.9.24`

## Causa raiz
No fluxo `expo prebuild`, o projeto nativo Android é regenerado. Sem configuração explícita, a versão de Kotlin pode ficar em par não compatível com o Compose Compiler trazido por dependências Expo/RN.

## Correção aplicada
- Adição do plugin `expo-build-properties` ao app.
- Configuração explícita em `app.json`:
  - `android.kotlinVersion = 1.9.25`
- Ajuste de workflows para garantir `NODE_ENV=production` durante prebuild/build release.

## Resultado esperado
- Eliminar erro de incompatibilidade Kotlin/Compose no `assembleRelease`.
- Tornar o prebuild determinístico em relação à versão de Kotlin.
