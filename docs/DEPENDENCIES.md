# Dependências verificadas

Versões fixadas e compiladas para Android mínimo API 33, alvo/compilação API 37. O ambiente de execução dos testes utiliza JDK 25; os alvos Java são 17.

| Componente | Versão | Fonte oficial |
| --- | --- | --- |
| Android Gradle Plugin | 9.1.1 | [Compatibilidade, Gradle 9.3.1 mínimo, JDK 17 e API 37](https://developer.android.com/build/releases/agp-9-1-0-release-notes) |
| Gradle Wrapper | 9.3.1 | [Distribuição Gradle](https://services.gradle.org/distributions/gradle-9.3.1-bin.zip) |
| Compose Compiler / Kotlin integrado AGP | 2.2.10 | [Plugin Compose para Kotlin 2.x](https://developer.android.com/jetpack/androidx/releases/compose-kotlin) |
| Compose BOM | 2026.09.00 | [Compose e BOM](https://developer.android.com/jetpack/androidx/releases/compose) |
| Activity Compose | 1.13.0 | [Activity estável](https://developer.android.com/jetpack/androidx/releases/activity) |
| Lifecycle Compose | 2.11.0 | [Lifecycle](https://developer.android.com/jetpack/androidx/releases/lifecycle) |
| Coroutines Android/Test | 1.11.0 | [Releases Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines/releases) |
| mwiede/JSch | 2.28.8 | [Release e código da versão](https://github.com/mwiede/jsch/releases/tag/jsch-2.28.8) |
| Bouncy Castle Provider | 1.86 | [Downloads oficiais](https://www.bouncycastle.org/download/bouncy-castle-java/) |
| AndroidX Test JUnit / Runner | 1.3.0 / 1.7.0 | [AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test) |
| UI Automator | 2.3.0 | [UI Automator](https://developer.android.com/jetpack/androidx/releases/test-uiautomator) |
| JUnit | 4.13.2 | [JUnit 4](https://github.com/junit-team/junit4/releases/tag/r4.13.2) |

As versões foram verificadas em fontes oficiais e pelo resolvedor Gradle, compilação, testes e Lint. AGP/Gradle não foram atualizados para versões mais novas apenas por aviso de atualização; foi mantida uma combinação estável e compatível com o projeto/Android Studio. O arquivo de arquitetura detalha a evidência do mecanismo de encaminhamento SSH, incluindo a tag exata analisada.
