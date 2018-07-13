/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.integtests.resolve

import com.google.common.collect.Maps
import org.gradle.api.internal.artifacts.configurations.ResolveConfigurationDependenciesBuildOperationType
import org.gradle.integtests.fixtures.AbstractHttpDependencyResolutionTest
import org.gradle.integtests.fixtures.BuildOperationNotificationsFixture
import org.gradle.integtests.fixtures.BuildOperationsFixture
import org.gradle.test.fixtures.plugin.PluginBuilder
import spock.lang.Unroll

class ResolveConfigurationRepositoriesBuildOperationIntegrationTest extends AbstractHttpDependencyResolutionTest {

    def operations = new BuildOperationsFixture(executer, temporaryFolder)

    @SuppressWarnings("GroovyUnusedDeclaration")
    def operationNotificationsFixture = new BuildOperationNotificationsFixture(executer, temporaryFolder)

    @Unroll
    def "repositories used when resolving project configurations are exposed via build operation, and are stable (repo: #repo)"() {
        setup:
        m2.generateUserSettingsFile(m2.mavenRepo())
        using m2
        buildFile << """
            apply plugin: 'java'
            ${repoBlock.replaceAll('<<URL>>', mavenHttpRepo.uri.toString())}
            task resolve { doLast { configurations.compile.resolve() } }
        """

        when:
        succeeds 'resolve'

        then:
        def op = operations.first(ResolveConfigurationDependenciesBuildOperationType)
        op.details.configurationName == 'compile'
        op.details.projectPath == ":"
        op.details.buildPath == ":"
        def repos = op.details.repositories
        repos.size() == 1
        stripRepoId(repos[0]) == augmentMapWithProperties(expectedRepo, [
            'URL': expectedRepo.name == 'MavenLocal' ? m2.mavenRepo().uri.toString() : mavenHttpRepo.uri.toString(),
            'Dirs': [buildFile.parentFile.file('fooDir').absolutePath]
        ])

        when:
        succeeds 'resolve'

        then: // stable
        repos == operations.first(ResolveConfigurationDependenciesBuildOperationType).details.repositories

        where:
        repo                   | repoBlock                     | expectedRepo
        'maven'                | mavenRepoBlock()              | expectedMavenRepo()
        'ivy'                  | ivyRepoBlock()                | expectedIvyRepo()
        'flat-dir'             | flatDirRepoBlock()            | expectedFlatDirRepo()
        'local maven'          | mavenLocalRepoBlock()         | expectedMavenLocalRepo()
        'maven central'        | mavenCentralRepoBlock()       | expectedMavenCentralRepo()
        'jcenter'              | jcenterRepoBlock()            | expectedJcenterRepo()
        'google'               | googleRepoBlock()             | expectedGoogleRepo()
        'gradle plugin portal' | gradlePluginPortalRepoBlock() | expectedGradlePluginPortalRepo()
    }

    def "repositories used in buildscript blocks are exposed via build operation, and are stable"() {
        setup:
        def module = mavenHttpRepo.module('org', 'foo')
        module.pom.expectGetBroken()
        buildFile << """     
            buildscript {
                repositories { maven { url '${mavenHttpRepo.uri}' } }
                dependencies { classpath 'org:foo:1.0' }
            }
        """

        when:
        fails 'help'

        then:
        def op = operations.first(ResolveConfigurationDependenciesBuildOperationType)
        op.details.configurationName == 'classpath'
        op.details.projectPath == null
        op.details.buildPath == ':'
        def repos = op.details.repositories
        repos.size() == 1
        with(repos[0]) {
            name == 'maven'
            type == 'maven'
            id
            properties == [
                URL: getMavenHttpRepo().uri.toString(),
                'Artifact URLs': [],
                'Metadata sources': ['mavenPom', 'artifact']
            ]
        }

        when:
        module.pom.expectGetBroken()
        fails 'help'

        then: // stable
        repos == operations.first(ResolveConfigurationDependenciesBuildOperationType).details.repositories
    }

    def "repositories used in plugins blocks are exposed via build operation, and are stable"() {
        setup:
        def module = mavenHttpRepo.module('my-plugin', 'my-plugin.gradle.plugin')
        module.pom.expectGetBroken()
        settingsFile << """
        pluginManagement {
            repositories { maven { url '${mavenHttpRepo.uri}' } }
        }
        """
        buildFile << """
            plugins { id 'my-plugin' version '1.0' }
        """

        when:
        fails 'help'

        then:
        def op = operations.first(ResolveConfigurationDependenciesBuildOperationType)
        op.details.configurationName == 'detachedConfiguration1'
        op.details.projectPath == null
        op.details.buildPath == ':'
        def repos = op.details.repositories
        repos.size() == 1
        with(repos[0]) {
            name == 'maven'
            type == 'maven'
            id
            properties == [
                URL: getMavenHttpRepo().uri.toString(),
                'Artifact URLs': [],
                'Metadata sources': ['mavenPom', 'artifact']
            ]
        }

        when:
        module.pom.expectGetBroken()
        fails 'help'

        then: // stable
        repos == operations.first(ResolveConfigurationDependenciesBuildOperationType).details.repositories
    }

    def "repositories shared across repository container types are stable"() {
        setup:
        publishTestPlugin('plugin', 'org.example.plugin', 'org.example.plugin:plugin:1.0')
        publishTestPlugin('plugin2', 'org.example.plugin2', 'org.example.plugin:plugin2:1.0')
        settingsFile << """
        pluginManagement {
            repositories { maven { url = '$mavenRepo.uri' } }
        }
        """
        buildFile << """
            buildscript {
                repositories { maven { url = '$mavenRepo.uri' } }
                dependencies { classpath "org.example.plugin:plugin2:1.0" }
            }
            plugins {
                id 'org.example.plugin' version '1.0'
                id 'java'
            }
            apply plugin: 'org.example.plugin2'
            repositories { maven { url = '$mavenRepo.uri' } }
            task resolve { doLast { configurations.compile.resolve() } }
        """

        when:
        succeeds 'resolve'

        then:
        def ops = operations.all(ResolveConfigurationDependenciesBuildOperationType)
        ops.size() == 3
        def opsWithRepos = ops.details.findAll { it.containsKey('repositories') }
        opsWithRepos.size() == 3
        opsWithRepos.repositories.getId.unique(false).size() == 1
    }

    def "repositories shared across projects are stable"() {
        setup:
        settingsFile << """
            include 'child'
        """
        buildFile << """
            allprojects { 
                apply plugin: 'java'
                repositories { jcenter() }
                task resolve { doLast { configurations.compile.resolve() } }
            }
        """

        when:
        succeeds 'resolve'

        then:
        def ops = operations.all(ResolveConfigurationDependenciesBuildOperationType)
        ops.details.repositories.size() == 2
        ops.details.repositories.unique(false).size() == 1
    }

    def "maven repository attributes are stored"() {
        setup:
        buildFile << """
            apply plugin: 'java'
            repositories {
                maven {
                    name = 'custom repo'
                    url = 'http://foo.com'
                    artifactUrls 'http://foo.com/artifacts1'
                    metadataSources { gradleMetadata(); artifact() }
                    credentials {
                        username 'user'
                        password 'pass'
                    }
                    authentication {
                        digest(DigestAuthentication)
                    }
                }
            }
            task resolve { doLast { configurations.compile.resolve() } }
        """

        when:
        succeeds 'resolve'

        then:
        def ops = operations.first(ResolveConfigurationDependenciesBuildOperationType)
        ops.details.repositories.size() == 1
        def repo = ops.details.repositories[0]
        with(repo) {
            name == 'custom repo'
            type == 'maven'
            id
            properties.size() == 5
            properties.URL == 'http://foo.com'
            properties.'Artifact URLs'.size() == 1
            properties.'Artifact URLs'[0].path == '/artifacts1'
            properties.'Metadata sources' == ['gradleMetadata', 'artifact']
            properties.Authenticated == true
            properties.'Authentication schemes' == ['DigestAuthentication']
        }
    }

    def "ivy repository attributes are stored"() {
        setup:
        buildFile << """
            apply plugin: 'java'
            repositories {
                ivy {
                    name = 'custom repo'
                    url 'http://myCompanyBucket/ivyrepo'
                    artifactPattern 'http://myCompanyBucket/ivyrepo/[organisation]/[module]/[artifact]-[revision]'
                    ivyPattern 'http://myCompanyBucket/ivyrepo/[organisation]/[module]/ivy-[revision].xml'
                    layout 'pattern', {
                        artifact '[module]/[organisation]/[revision]/[artifact]'
                        artifact '3rd-party/[module]/[organisation]/[revision]/[artifact]'
                        ivy '[module]/[organisation]/[revision]/ivy.xml'
                        m2compatible = true
                    }
                    metadataSources { gradleMetadata(); ivyDescriptor(); artifact() }
                    credentials {
                        username 'user'
                        password 'pass'
                    }
                    authentication {
                        basic(BasicAuthentication)
                    }
                }
            }
            task resolve { doLast { configurations.compile.resolve() } }
        """

        when:
        succeeds 'resolve'

        then:
        def ops = operations.first(ResolveConfigurationDependenciesBuildOperationType)
        ops.details.repositories.size() == 1
        def repo = ops.details.repositories[0]
        with(repo) {
            name == 'custom repo'
            type == 'ivy'
            id
            properties.size() == 8
            properties.URL == 'http://myCompanyBucket/ivyrepo'
            properties.Layout == 'Pattern'
            properties.'M2 compatible' == true
            properties.'Ivy patterns' == [
                '[module]/[organisation]/[revision]/ivy.xml',
                'http://myCompanyBucket/ivyrepo/[organisation]/[module]/ivy-[revision].xml'
            ]
            properties.'Artifact patterns' == [
                '[module]/[organisation]/[revision]/[artifact]',
                '3rd-party/[module]/[organisation]/[revision]/[artifact]',
                'http://myCompanyBucket/ivyrepo/[organisation]/[module]/[artifact]-[revision]'
            ]
            properties.'Metadata sources' == ['gradleMetadata', 'ivyDescriptor', 'artifact']
            properties.Authenticated == true
            properties.'Authentication schemes' == ['BasicAuthentication']
        }
    }

    def "flat-dir repository attributes are stored"() {
        setup:
        buildFile << """
            apply plugin: 'java'
            repositories {
                flatDir {
                    name = 'custom repo'
                    dirs 'lib1', 'lib2'
                }
            }
            task resolve { doLast { configurations.compile.resolve() } }
        """

        when:
        succeeds 'resolve'

        then:
        def ops = operations.first(ResolveConfigurationDependenciesBuildOperationType)
        ops.details.repositories.size() == 1
        def repo = ops.details.repositories[0]
        with(repo) {
            name == 'custom repo'
            type == 'flat_dir'
            id
            properties.size() == 1
            properties.Dirs.sort() == [file('lib1').absolutePath, file('lib2').absolutePath].sort()
        }
    }

    private static String mavenRepoBlock() {
        "repositories { maven { url '<<URL>>' } }"
    }

    private static Map expectedMavenRepo() {
        [
            name: 'maven',
            type: 'maven',
            properties: [
                URL: null,
                'Artifact URLs': [],
                'Metadata sources': ['mavenPom', 'artifact']
            ]
        ]
    }

    private static String ivyRepoBlock() {
        "repositories { ivy { url '<<URL>>' } }"
    }

    private static Map expectedIvyRepo() {
        [
            name: 'ivy',
            type: 'ivy',
            properties: [
                URL: null,
                Layout: 'Gradle',
                'M2 compatible': false,
                'Ivy patterns': ['[organisation]/[module]/[revision]/ivy-[revision].xml'],
                'Artifact patterns': ['[organisation]/[module]/[revision]/[artifact]-[revision](-[classifier])(.[ext])'],
                'Metadata sources': ['ivyDescriptor', 'artifact']
            ]
        ]
    }

    private static String flatDirRepoBlock() {
        "repositories { flatDir { dirs 'fooDir' } }"
    }

    private static Map expectedFlatDirRepo() {
        [
            name: 'flatDir',
            type: 'flat_dir',
            properties: [
                Dirs: null
            ]
        ]
    }

    private static String mavenLocalRepoBlock() {
        "repositories { mavenLocal() }"
    }

    private static Map expectedMavenLocalRepo() {
        [
            name: 'MavenLocal',
            type: 'maven',
            properties: [
                URL: null,
                'Artifact URLs': [],
                'Metadata sources': ['mavenPom', 'artifact']
            ]
        ]
    }

    private static String mavenCentralRepoBlock() {
        "repositories { mavenCentral() }"
    }


    private static Map expectedMavenCentralRepo() {
        [
            name: 'MavenRepo',
            type: 'maven',
            properties: [
                URL: 'https://repo.maven.apache.org/maven2/',
                'Artifact URLs': [],
                'Metadata sources': ['mavenPom', 'artifact']
            ]
        ]
    }

    private static String jcenterRepoBlock() {
        "repositories { jcenter() }"
    }

    private static Map expectedJcenterRepo() {
        [
            name: 'BintrayJCenter',
            type: 'maven',
            properties: [
                URL: 'https://jcenter.bintray.com/',
                'Artifact URLs': [],
                'Metadata sources': ['mavenPom', 'artifact']
            ]
        ]
    }

    private static String googleRepoBlock() {
        "repositories { google() }"
    }

    private static Map expectedGoogleRepo() {
        [
            name: 'Google',
            type: 'maven',
            properties: [
                URL: 'https://dl.google.com/dl/android/maven2/',
                'Artifact URLs': [],
                'Metadata sources': ['mavenPom', 'artifact']
            ]
        ]
    }

    private static String gradlePluginPortalRepoBlock() {
        "repositories { gradlePluginPortal() }"
    }

    private static Map expectedGradlePluginPortalRepo() {
        [
            name: 'Gradle Central Plugin Repository',
            type: 'maven',
            properties: [
                URL: 'https://plugins.gradle.org/m2',
                'Artifact URLs': [],
                'Metadata sources': ['mavenPom', 'artifact']
            ]
        ]
    }

    private static Map<String, ?> stripRepoId(Map<String, ?> map) {
        assert map.containsKey('id')
        def returnedMap = Maps.newHashMap(map)
        returnedMap.remove('id')
        returnedMap
    }

    private static Map<String, ?> augmentMapWithProperties(Map<String, ?> map, Map<String, ?> replacements) {
        assert map.containsKey('properties')
        replacements.each { k, v ->
            if (map.get('properties').containsKey(k) && map.get('properties').get(k) == null) {
                map.get('properties').put(k, v)
            }
        }
        map
    }

    private publishTestPlugin(String path, String id, String coordinates) {
        def pluginBuilder = new PluginBuilder(testDirectory.file(path))
        def message = "from plugin"
        def taskName = "pluginTask"
        pluginBuilder.addPluginWithPrintlnTask(taskName, message, id)
        pluginBuilder.publishAs(coordinates, mavenRepo, executer)
    }

}
