@Library('ci-common@v1.0.0') _

pipeline {
    agent { label 'php' }

    options {
        timeout(time: 20, unit: 'MINUTES')
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    parameters {
        choice(name: 'TARGET_ENV', choices: ['staging', 'production'],
               description: 'Environment to deploy to')
        booleanParam(name: 'ROLLBACK', defaultValue: false,
                     description: 'Skip the gates and switch back to the previous slot')
    }

    environment {
        GATE_RECORDS = "${WORKSPACE}/app/reports/gates"
    }

    stages {
        stage('Install') {
            when { not { expression { params.ROLLBACK } } }
            steps { dir('app') { sh 'composer install --no-interaction --no-progress --prefer-dist' } }
        }
        // Gates run from the cheapest to the most expensive. Coverage and CRAP only read
        // what the unit tests already wrote, so they cost almost nothing.
        stage('Quality gates') {
            when { not { expression { params.ROLLBACK } } }
            stages {
                stage('Code style') {
                    steps {
                        dir('app') {
                            runGate(name: 'Code style', type: 'checkstyle',
                                    command: 'vendor/bin/php-cs-fixer check --format=checkstyle',
                                    report: 'reports/php-cs-fixer.xml')
                        }
                    }
                }
                stage('Static analysis') {
                    steps {
                        dir('app') {
                            runGate(name: 'Static analysis', type: 'phpstan',
                                    command: 'vendor/bin/phpstan analyse --no-progress --error-format=json',
                                    report: 'reports/phpstan.json')
                        }
                    }
                }
                stage('Dependency audit') {
                    steps {
                        dir('app') {
                            runGate(name: 'Dependency audit', type: 'composer-audit',
                                    command: 'composer audit --locked --format=json',
                                    report: 'reports/composer-audit.json')
                        }
                    }
                }
                stage('Unit tests') {
                    steps {
                        dir('app') {
                            runGate(name: 'Unit tests',
                                    command: 'vendor/bin/phpunit --log-junit reports/junit/phpunit.xml ' +
                                             '--coverage-cobertura reports/coverage/cobertura.xml ' +
                                             '--coverage-crap4j reports/coverage/crap4j.xml',
                                    report: 'reports/phpunit.txt')
                        }
                    }
                }
                stage('Coverage') {
                    steps { dir('app') { coverageGate(report: 'reports/coverage/cobertura.xml', min: 80) } }
                }
                stage('Complexity (CRAP)') {
                    steps { dir('app') { crapGate(report: 'reports/coverage/crap4j.xml', max: 30) } }
                }
            }
        }
        stage('Package') {
            when { not { expression { params.ROLLBACK } } }
            steps { sh 'app/bin/package dist/release' }
        }
        stage('Deploy') {
            when { not { expression { params.ROLLBACK } } }
            steps {
                deployBlueGreen(environment: params.TARGET_ENV, buildDir: 'dist/release',
                                report: 'app/reports/deploy.json')
            }
        }
        stage('Rollback') {
            when { expression { params.ROLLBACK } }
            steps {
                deployBlueGreen(environment: params.TARGET_ENV, rollback: true, report: 'app/reports/deploy.json')
            }
        }
    }

    post {
        always {
            junit allowEmptyResults: true, testResults: 'app/reports/junit/*.xml'
            recordCoverage tools: [[parser: 'COBERTURA', pattern: 'app/reports/coverage/cobertura.xml']],
                           sourceCodeRetention: 'NEVER', skipPublishingChecks: true
            // The summary is a file under app/reports, so write it before archiving.
            notifyBuild(reportsDir: 'app/reports')
            archiveArtifacts allowEmptyArchive: true, artifacts: 'app/reports/**'
        }
        cleanup { cleanWs() }
    }
}
