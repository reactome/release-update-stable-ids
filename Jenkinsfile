import groovy.json.JsonSlurper
// This Jenkinsfile is used by Jenkins to run the UpdateStableIdentifiers step of Reactome's release.
// It requires that the ConfirmReleaseConfigs step has been run successfully before it can be run.
def currentRelease
def previousRelease
pipeline {
	agent any

	stages {
		// This stage checks that an upstream project, ConfirmReleaseConfig, was run successfully for its last build.
		stage('Check ConfirmReleaseConfig build succeeded'){
			steps{
				script{
					// Get current release number from directory
					currentRelease = (pwd() =~ /Releases\/(\d+)\//)[0][1];
					previousRelease = (pwd() =~ /Releases\/(\d+)\//)[0][1].toInteger() - 1;
					// This queries the Jenkins API to confirm that the most recent build of ConfirmReleaseConfigs was successful.
					def configStatusUrl = httpRequest authentication: 'jenkinsKey', validResponseCodes: "${env.VALID_RESPONSE_CODES}", url: "${env.JENKINS_JOB_URL}/job/${currentRelease}/job/ConfirmReleaseConfigs/lastBuild/api/json"
					if (configStatusUrl.getStatus() == 404) {
						error("ConfirmReleaseConfigs has not yet been run. Please complete a successful build.")
					} else {
						def configStatusJson = new JsonSlurper().parseText(configStatusUrl.getContent())
						if (configStatusJson['result'] != "SUCCESS"){
							error("Most recent ConfirmReleaseConfigs build status: " + configStatusJson['result'] + ". Please complete a successful build.")
						}
					}
				}
			}
		}
		// This stage moves 'slice_current' database to 'slice_previous', and then moves 'slice_test' to 'slice_current'.
		// It also saves the slice_test dump as a snapshot, to be used in the next release.
		stage('Setup: Rotate slice DBs'){
			steps{
				script{
					withCredentials([usernamePassword(credentialsId: 'mySQLUsernamePassword', passwordVariable: 'pass', usernameVariable: 'user')]){
						def slice_test_snapshot_dump = "${env.SLICE_TEST}_${currentRelease}_snapshot.dump"
						def slice_previous_snapshot_dump = "${env.SLICE_TEST}_${previousRelease}_snapshot.dump.gz"
						// Retrieve snapshot DB from S3.
						sh "aws s3 --no-progress cp ${env.S3_RELEASE_DIRECTORY_URL}/${previousRelease}/update_stable_ids/$slice_previous_snapshot_dump ."
						sh "mysql -u$user -p$pass -e \'drop database if exists ${env.SLICE_PREVIOUS}; create database ${env.SLICE_PREVIOUS}\'"
						sh "zcat  $slice_previous_snapshot_dump 2>&1 | mysql -u$user -p$pass ${env.SLICE_PREVIOUS}"
						sh "rm $slice_previous_snapshot_dump"
						sh "mysqldump -u$user -p$pass ${env.SLICE_TEST} > $slice_test_snapshot_dump"
						sh "gzip -f $slice_test_snapshot_dump"
						sh "mysql -u$user -p$pass -e \'drop database if exists ${env.SLICE_CURRENT}; create database ${env.SLICE_CURRENT}\'"
						sh "zcat  ${env.SLICE_TEST}_${currentRelease}_snapshot.dump.gz 2>&1 | mysql -u$user -p$pass ${env.SLICE_CURRENT}"
					}
				}
			}
		}
		// This stage backs up the gk_central database before it is modified.
		stage('Setup: Back up Curator gk_central DB'){
			steps{
				script{
					withCredentials([usernamePassword(credentialsId: 'mySQLCuratorUsernamePassword', passwordVariable: 'pass', usernameVariable: 'user')]){
						def central_before_update_stable_ids_dump = "${env.GK_CENTRAL}_${currentRelease}_before_st_id.dump"
						sh "mysqldump -u$user -p$pass -h${env.CURATOR_SERVER} ${env.GK_CENTRAL} > $central_before_update_stable_ids_dump"
						sh "gzip -f $central_before_update_stable_ids_dump"
					}
				}
			}
		}
		// This stage builds the jar file using maven.
		stage('Setup: Build jar files'){
			steps{
				script{
					sh "mvn clean compile assembly:single"
					sh "git clone https://github.com/reactome/data-release-pipeline"
					// Temporary approach to setting up post-step QA artifact, which comes from a branch in data-release-pipeline. 
					// Eventually will be moved to something like the release-common-lib repo.
					dir("data-release-pipeline"){
						sh "git checkout feature/post-step-tests-stid-history"
						dir("ortho-stable-id-history"){
							sh "mvn clean compile assembly:single"
						}
					}
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
		// This stage creates a new 'release_current' database from the freshly updated 'slice_current' database.
		// This will be the primary database used throughout release from here.
		stage('Post: Create release_current from slice_current'){
			steps{
				script{
					withCredentials([usernamePassword(credentialsId: 'mySQLUsernamePassword', passwordVariable: 'pass', usernameVariable: 'user')]) {
						sh "mysql -u$user -p$pass -e \'drop database if exists ${env.RELEASE_CURRENT}; create database ${env.RELEASE_CURRENT}\'"
						sh "mysqldump --opt -u$user -p$pass ${env.SLICE_CURRENT} | mysql -u$user -p$pass ${env.RELEASE_CURRENT}"
					}
				}
			}
		}
		// QA for ensuring StableIdentifier instances are proper.
		stage('Post: StableIdentifier QA'){
			steps{
				script{
					dir("data-release-pipeline/ortho-stable-id-history"){
						withCredentials([file(credentialsId: 'Config', variable: 'ConfigFile')]) {
							sh "java -jar target/OrthoStableIdHistory-*-jar-with-dependencies.jar $ConfigFile"
						}
					}
				}
			}
		}
		// This stage backs up the gk_central and slice_current databases after they have been modified.
		stage('Post: Backup DBs'){
			steps{
				script{
					withCredentials([usernamePassword(credentialsId: 'mySQLUsernamePassword', passwordVariable: 'pass', usernameVariable: 'user')]){
						withCredentials([usernamePassword(credentialsId: 'mySQLCuratorUsernamePassword', passwordVariable: 'curPass', usernameVariable: 'curUser')]){
							def slice_current_after_update_stable_ids_dump = "${env.SLICE_CURRENT}_${currentRelease}_after_st_id.dump"
							def central_after_update_stable_ids_dump = "${env.GK_CENTRAL}_${currentRelease}_after_st_id.dump"
							sh "mysqldump -u$user -p$pass ${env.SLICE_CURRENT} > $slice_current_after_update_stable_ids_dump"
							sh "gzip -f $slice_current_after_update_stable_ids_dump"
							sh "mysqldump -u$curUser -p$curPass -h${env.CURATOR_SERVER} ${env.GK_CENTRAL} > $central_after_update_stable_ids_dump"
							sh "gzip -f $central_after_update_stable_ids_dump"
						}
					}
				}
			}
		}
		// This stage archives all logs and database backups produced by UpdateStableIdentifiers in the Reactome s3 bucket.
		stage('Archive logs and backups'){
			steps{
				script{
					def s3Path = "${env.S3_RELEASE_DIRECTORY_URL}/${currentRelease}/update_stable_ids"
					sh "mkdir -p databases/"
					sh "mv --backup=numbered *_${currentRelease}_*.dump.gz databases/"
					sh "mv data-release-pipeline/ortho-stable-id-history/logs/* logs/"
					sh "rm -rf data-release-pipeline*"
					sh "gzip logs/*"
					sh "aws s3 --no-progress --recursive cp databases/ $s3Path/databases/"
					sh "aws s3 --no-progress --recursive cp logs/ $s3Path/logs/"
					sh "rm -r databases"
					sh "rm -r logs"
				}
			}
		}
	}
}
