// This Jenkinsfile is used by Jenkins to run the UpdateStableIdentifiers step of Reactome's release.
// It requires that the ConfirmReleaseConfigs step has been run successfully before it can be run.

import org.reactome.release.jenkins.utilities.Utilities

// Shared library maintained at 'release-jenkins-utils' repository.
def utils = new Utilities()

pipeline {
	agent any

	stages {
		// This stage checks that an upstream project, ConfirmReleaseConfig, was run successfully for its last build.
		stage('Check ConfirmReleaseConfig build succeeded'){
			steps{
				script{
					utils.checkUpstreamBuildsSucceeded("ConfirmReleaseConfigs")
				}
			}
		}
		// This stage moves 'slice_current' database to 'slice_previous', and then moves 'slice_test' to 'slice_current'.
		// It also saves the slice_test dump as a snapshot, to be used in the next release.
		stage('Setup: Rotate slice DBs'){
			steps{
				script{
					withCredentials([usernamePassword(credentialsId: 'mySQLUsernamePassword', passwordVariable: 'pass', usernameVariable: 'user')]){
						
						// Replace 'slice_previous' db with 'snapshot' slice DB from S3.
						def previousReleaseVersion = utils.getPreviousReleaseVersion()
						def slicePreviousSnapshotDump = "${env.SLICE_TEST_DB}_${previousReleaseVersion}_snapshot.dump.gz"
						// Retrieve previous releases' snapshot slice DB from S3.
						sh "aws s3 --no-progress cp ${env.S3_RELEASE_DIRECTORY_URL}/${previousReleaseVersion}/update_stable_ids/databases/${slicePreviousSnapshotDump} ."
						utils.replaceDatabase("${env.SLICE_PREVIOUS_DB}", "${slicePreviousSnapshotDump}")
						sh "rm ${slicePreviousSnapshotDump}"
						
						// Replace 'slice_current' DB with 'slice_test' DB from dump.
						def releaseVersion = utils.getReleaseVersion()
						def sliceTestSnapshotDump = "${SLICE_TEST_DB}_${releaseVersion}_snapshot.dump"
						utils.takeDatabaseDump("${env.SLICE_TEST_DB}", "${sliceTestSnapshotDump}", "${env.RELEASE_SERVER}")
						sh "gzip -f ${sliceTestSnapshotDump}"
						utils.replaceDatabase("${env.SLICE_CURRENT_DB}", "${sliceTestSnapshotDump}.gz")
					}
				}
			}
		}
		// This stage backs up the gk_central database before it is modified.
		stage('Setup: Back up Curator gk_central DB'){
			steps{
				script{
					withCredentials([usernamePassword(credentialsId: 'mySQLCuratorUsernamePassword', passwordVariable: 'pass', usernameVariable: 'user')]){
						utils.takeDatabaseDumpAndGzip("${env.GK_CENTRAL_DB}", "update_stable_ids", "before", "${env.CURATOR_SERVER}")
					}
				}
			}
		}
		// This stage builds the jar file using maven.
		stage('Setup: Build jar files'){
			steps{
				script{
					utils.buildJarFile()
				}
			}
		}
		// This stage executes the UpdateStableIdentifiers jar file. It will go through all human stable identifier instances, comparing them between releases.
		// Any that have an increase in the number of 'modified' instances between releases will be incremented in release_current and gk_central (on the curator server).
		stage('Main: Update Stable Identifiers'){
			steps {
				script{
					withCredentials([file(credentialsId: 'Config', variable: 'ConfigFile')]){
						sh "java -Xmx${env.JAVA_MEM_MAX}m -jar target/update-stable-ids-*-jar-with-dependencies.jar $ConfigFile"
					}
				}
			}
		}
		// This stage runs StableIdentifier QA, which checks all stable ids in the database for valid formats.
		// Currently this module comes from data-release-pipeline@feature/post-step-stid-history, but in
		// the future will be moved to a QA repository, specific to release.
		stage('Post: Build and run StableIdentifier QA') {
			steps{
				script{
					// Clone release-qa and run the StableIdentifierVersionMistmatch QA check
				    	utils.cloneOrUpdateLocalRepo("release-qa")
					dir("release-qa") {
						sh "git checkout main-release"
						utils.buildJarFile()
						sh "ln -sf src/main/resources/ resources"

						withCredentials([file(credentialsId: 'Config', variable: 'ConfigFile')]){
						    sh "cp $ConfigFile resources/auth.properties"
						    sh "java -Xmx${env.JAVA_MEM_MAX}m -jar target/release-qa-*-jar-with-dependencies.jar StableIdentifierVersionMismatchCheck"
						    sh "rm resources/auth.properties"
						}
				    
				    	}
					// Clone data-release-pipeline and checkout specific branch
					utils.cloneOrUpdateLocalRepo("data-release-pipeline")
					dir("data-release-pipeline") {
				        	sh "git checkout develop"
				        
						// Build and run QA jar file
						dir("ortho-stable-id-history") {
							utils.buildJarFile()
							withCredentials([file(credentialsId: 'Config', variable: 'ConfigFile')]) {
								sh "java -jar target/OrthoStableIdHistory-*-jar-with-dependencies.jar $ConfigFile"
							}
						}
					}
				}
			}
		}
		// This stage creates a new 'release_previous' database from the 'release_current' database,
		// and takes the recently updated 'slice_current' database and creates a new 'release_current' one.
		stage('Post: Rotate release DBs'){
			steps{
				script{
					withCredentials([usernamePassword(credentialsId: 'mySQLUsernamePassword', passwordVariable: 'pass', usernameVariable: 'user')]) {
						
						// Take existing 'release_current' DB and replace 'release_previous' with it.
						// TODO this should come from S3 final DB resting place.
						def previousReleaseVersion = utils.getPreviousReleaseVersion()
						def releaseCurrentDBToBeReplacedDumpName = "${env.RELEASE_CURRENT_DB}_${previousReleaseVersion}_final.dump"
						utils.takeDatabaseDump("${env.RELEASE_CURRENT_DB}", "${releaseCurrentDBToBeReplacedDumpName}", "${env.RELEASE_SERVER}")
				      		sh "gzip ${releaseCurrentDBToBeReplacedDumpName}"
						utils.replaceDatabase("${env.RELEASE_PREVIOUS_DB}", "${releaseCurrentDBToBeReplacedDumpName}.gz")
						sh "rm ${releaseCurrentDBToBeReplacedDumpName}.gz"
						
						// Replace 'release_current' DB with updated 'slice_current' DB.
				    		def releaseVersion = utils.getReleaseVersion()
						def sliceCurrentAfterStableIdUpdateDump = utils.takeDatabaseDumpAndGzip("${env.SLICE_CURRENT_DB}", "update_stable_ids", "after", "${env.RELEASE_SERVER}")
				 		utils.replaceDatabase("${env.RELEASE_CURRENT_DB}", "${sliceCurrentAfterStableIdUpdateDump}")
					}
				}
			}
		}
		// This stage backs up the gk_central and slice_current databases after they have been modified.
		stage('Post: Back up Curator gk_central DB'){
			steps{
				script{
					withCredentials([usernamePassword(credentialsId: 'mySQLCuratorUsernamePassword', passwordVariable: 'pass', usernameVariable: 'user')]){
                        			utils.takeDatabaseDumpAndGzip("${env.GK_CENTRAL_DB}", "update_stable_ids", "after", "${env.CURATOR_SERVER}")
					}
				}
			}
		}
		// Archives logs and databases on S3, and then everything on the server.
		stage('Post: Archive Outputs'){
			steps{
				script{
					def releaseVersion = utils.getReleaseVersion()
					def slice_final_folder = "slice_final/"
					// Create copies of the 'slice_test' and 'slice_current_after_stable_ids' databases, storing them in the
					// slice_final directory on S3 as 'test_slice_XX_snapshot.dump.gz' and 'test_slice_XX.dump.gz', respectively.
					sh "cp ${SLICE_CURRENT_DB}_${releaseVersion}_after_update_stable_ids*dump.gz test_slice_${releaseVersion}.dump.gz"
					sh "cp ${SLICE_TEST_DB}_${releaseVersion}_snapshot.dump.gz test_slice_${releaseVersion}_snapshot.dump.gz"
					
					sh "mkdir -p ${slice_final_folder}"
					sh "mv -f test_slice_${releaseVersion}*dump.gz ${slice_final_folder}"
					sh "aws s3 --no-progress cp --recursive ${slice_final_folder} s3://reactome/private/databases/${slice_final_folder}"
					
					def dataFiles = ["release-qa/output/*"]
					// Additional log files from post-step QA need to be pulled in
					def logFiles = ["data-release-pipeline/ortho-stable-id-history/logs/*"]
					// This folder is utilized for post-step QA. Jenkins creates multiple temporary directories
					// cloning and checking out repositories, which is why the wildcard is added.
					def foldersToDelete = ["data-release-pipeline*", "release-qa*", "${slice_final_folder}"]
					utils.cleanUpAndArchiveBuildFiles("update_stable_ids", dataFiles, logFiles, foldersToDelete)
				}
			}
		}
	}
}
