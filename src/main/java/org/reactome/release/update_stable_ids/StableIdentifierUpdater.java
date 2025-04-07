package org.reactome.release.update_stable_ids;

import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.gk.util.GKApplicationUtilities;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Transaction;
import org.reactome.server.service.model.GKInstance;
import org.reactome.server.service.model.InstanceDisplayNameGenerator;
import org.reactome.server.service.model.ReactomeJavaConstants;
import org.reactome.server.service.persistence.Neo4JAdaptor;
import org.reactome.server.service.schema.SchemaClass;

public class StableIdentifierUpdater {

	private static final Logger logger = LogManager.getLogger();

	private Neo4JAdaptor dbaSlice;
	private Neo4JAdaptor dbaPrevSlice;
	private Neo4JAdaptor dbaGKCentral;
	private long personId;

	private GKInstance sliceInstanceEdit;
	private GKInstance gkCentralInstanceEdit;

	public StableIdentifierUpdater(
		Neo4JAdaptor dbaSlice, Neo4JAdaptor dbaPrevSlice, Neo4JAdaptor dbaGkCentral, long personId) {

		this.dbaSlice = dbaSlice;
		this.dbaPrevSlice = dbaPrevSlice;
		this.dbaGKCentral = dbaGkCentral;
		this.personId = personId;
	}

	@SuppressWarnings("unchecked")
	public void update() throws Exception {
		int incrementedCount = 0;
		int notIncrementedCount = 0;

		try (Session gkCentralSession = getDbaGKCentral().getConnection().session(SessionConfig.forDatabase(getDbaGKCentral().getDBName()));
			 Session sliceSession = getDbaSlice().getConnection().session(SessionConfig.forDatabase(getDbaSlice().getDBName()));
			 Transaction gkCentralTx = gkCentralSession.beginTransaction();
			 Transaction sliceTx = sliceSession.beginTransaction()) {
			//TODO: Perl wrapper will create a 'snapshot' of the previous slice -- once the wrapper is retired this needs to be done

			List<GKInstance> sliceInstances = getSliceInstances();
			logger.info("Total instances to check: " + sliceInstances.size());
			for (GKInstance sliceInstance : sliceInstances) {
				logger.info("Checking " + sliceInstance);
				GKInstance gkCentralInstance = getDbaGKCentral().fetchInstance(sliceInstance.getDBID());
				GKInstance prevSliceInstance = getDbaPrevSlice().fetchInstance(sliceInstance.getDBID());
				// Check if instance is new and that it exists on gkCentral (they could be deleted)
				if (prevSliceInstance == null || gkCentralInstance == null) {
					if (gkCentralInstance == null) {
						logger.warn(sliceInstance + " -- Instance not found in gkCentral");
					}
					continue;
				}

				// Compare number of 'Update Tracker' instances between slices
				Collection<GKInstance> sliceInstanceUpdateTracker = getUpdateTrackerInstances(sliceInstance);
				Collection<GKInstance> prevSliceUpdateTracker = getUpdateTrackerInstances(prevSliceInstance);

				if (sliceInstanceUpdateTracker.size() < prevSliceUpdateTracker.size()) {
					String errorMessage =
						sliceInstance + " in current release has fewer update tracker instances than previous release";
					logger.fatal(errorMessage);
					throw new IllegalStateException(errorMessage);
				}

				if (sliceInstanceUpdateTracker.size() > prevSliceUpdateTracker.size()) {
					boolean incrementSuccessful = attemptIncrementOfStableId(
						sliceInstance, gkCentralInstance, sliceTx, gkCentralTx, prevSliceInstance);
					if (incrementSuccessful) {
						incrementedCount++;
					}
				} else {
					notIncrementedCount++;
				}
				// Instances that have been updated already during the current release will have their 'releaseStatus'
				// attribute equal to 'UPDATED'.
				// This will make sure that StableIDs are only updated once per release.
				try {
					if (isUpdated(sliceInstance, prevSliceInstance)) {
						logger.info("Checking if " + sliceInstance + " needs to be updated");
						String releaseStatusString = (String) sliceInstance.getAttributeValue(ReactomeJavaConstants.releaseStatus);
						String updated = "UPDATED";

						if (releaseStatusString == null || !releaseStatusString.equals(updated)) {
							logger.info("Updating release status for " + sliceInstance);
							sliceInstance.addAttributeValue(ReactomeJavaConstants.releaseStatus, updated);
							sliceInstance.addAttributeValue(ReactomeJavaConstants.modified, getSliceInstanceEdit(sliceTx));
							getDbaSlice().updateInstanceAttribute(sliceInstance, ReactomeJavaConstants.releaseStatus, sliceTx);
							getDbaSlice().updateInstanceAttribute(sliceInstance, ReactomeJavaConstants.modified, sliceTx);
						} else {
							logger.info("StableIdentifier has already been updated during this release");
						}
					}
				} catch (Exception e) {
					logger.error("Unable to check if {} was updated", sliceInstance, e);
				}
			}

			sliceTx.commit();
			gkCentralTx.commit();
		} catch (Exception e) {
			logger.error("Problem with session transaction");
		}

		logger.info(incrementedCount + " Stable Identifiers were updated");
		logger.info(notIncrementedCount + " were not updated");
		logger.info("UpdateStableIdentifiers step has finished");
	}

	private GKInstance getSliceInstanceEdit(Transaction tx) throws Exception {
		if (this.sliceInstanceEdit == null) {
			this.sliceInstanceEdit = getInstanceEdit(getDbaSlice(), tx);
		}

		return this.sliceInstanceEdit;
	}

	private GKInstance getGkCentralInstanceEdit(Transaction tx) throws Exception {
		if (this.gkCentralInstanceEdit == null) {
			this.gkCentralInstanceEdit = getInstanceEdit(getDbaGKCentral(), tx);
		}

		return this.gkCentralInstanceEdit;
	}

	private GKInstance getInstanceEdit(Neo4JAdaptor dbAdaptor, Transaction tx) throws Exception {
		final String note = "org.reactome.release.updateStableIds";

		GKInstance personInstance = dbAdaptor.fetchInstance(getPersonId());
		if (personInstance == null) {
			throw new Exception("Could not fetch Person entity with ID " + getPersonId() + ". Please check that a " +
				"Person entity exists in the database with this ID.");
		}
		GKInstance instanceEdit = new GKInstance();
		instanceEdit.setDbAdaptor(dbAdaptor);
		SchemaClass cls = dbAdaptor.getSchema().getClassByName(ReactomeJavaConstants.InstanceEdit);
		instanceEdit.setSchemaClass(cls);

		instanceEdit.addAttributeValue(ReactomeJavaConstants.author, personInstance);
		instanceEdit.addAttributeValue(ReactomeJavaConstants.dateTime, GKApplicationUtilities.getDateTime());
		instanceEdit.addAttributeValue(ReactomeJavaConstants.note, note);
		InstanceDisplayNameGenerator.setDisplayName(instanceEdit);

		dbAdaptor.storeInstance(instanceEdit, tx);

		return instanceEdit;
	}

	private List<GKInstance> getSliceInstances() throws Exception {
		logger.info("Fetching all Event and PhysicalEntity instances");
		// Get all Event and PhysicalEntity instances and combine them into one large List
		List<GKInstance> sliceInstances = new ArrayList<>();

		Collection<GKInstance> eventInstances =
			getDbaSlice().fetchInstancesByClass(ReactomeJavaConstants.Event);
		Collection<GKInstance> physicalEntityInstances =
			getDbaSlice().fetchInstancesByClass(ReactomeJavaConstants.PhysicalEntity);
		sliceInstances.addAll(eventInstances);
		sliceInstances.addAll(physicalEntityInstances);
		return sliceInstances;
	}

	private List<GKInstance> getUpdateTrackerInstances(GKInstance instance) throws Exception {
		Collection<GKInstance> updateTrackerInstances = instance.getReferers("updatedInstance");
		return updateTrackerInstances != null ? new ArrayList<>(updateTrackerInstances) : new ArrayList<>();
	}

	private boolean attemptIncrementOfStableId(GKInstance sliceInstance,
											   GKInstance gkCentralInstance,
											   Transaction sliceTx,
											   Transaction gkCentralTx,
											   GKInstance prevSliceInstance) throws Exception {
		// Make sure StableIdentifier instance exists
		if (sliceInstance.getAttributeValue(ReactomeJavaConstants.stableIdentifier) != null &&
			gkCentralInstance.getAttributeValue(ReactomeJavaConstants.stableIdentifier) != null) {

			logger.info("\tIncrementing " + sliceInstance.getAttributeValue(ReactomeJavaConstants.stableIdentifier));
			incrementStableIdentifier(sliceInstance, getDbaSlice(), sliceTx, getSliceInstanceEdit(sliceTx));
			incrementStableIdentifier(gkCentralInstance, getDbaGKCentral(), gkCentralTx, getGkCentralInstanceEdit(gkCentralTx));
			return true;
		} else if (sliceInstance.getAttributeValue(ReactomeJavaConstants.stableIdentifier) == null){
			logger.warn(sliceInstance + ": could not locate StableIdentifier instance");
		} else {
			logger.warn(prevSliceInstance + ": Instance from previous slice did not have StableIdentifier instance");
		}
		return false;
	}

	// Increments the identifierVersion attribute and updates the StableIdentifier displayName accordingly.
	// TODO: Integration Testing of increment function
	private void incrementStableIdentifier(GKInstance instance, Neo4JAdaptor dba, Transaction tx, GKInstance instanceEdit) throws Exception {
		GKInstance stableIdentifierInst = (GKInstance) instance.getAttributeValue(ReactomeJavaConstants.stableIdentifier);
		String id = (String) stableIdentifierInst.getAttributeValue(ReactomeJavaConstants.identifier);
		int idVersion = Integer.parseInt((String) stableIdentifierInst.getAttributeValue(ReactomeJavaConstants.identifierVersion));
		int newIdentifierVersion = idVersion + 1;

		stableIdentifierInst.addAttributeValue(ReactomeJavaConstants.identifierVersion, String.valueOf(newIdentifierVersion));
		stableIdentifierInst.setDisplayName(id + "." + newIdentifierVersion);
		Collection<GKInstance> modifiedInstances = (Collection<GKInstance>) stableIdentifierInst.getAttributeValuesList(ReactomeJavaConstants.modified);
		stableIdentifierInst.addAttributeValue(ReactomeJavaConstants.modified, instanceEdit);
		dba.updateInstanceAttribute(stableIdentifierInst, ReactomeJavaConstants.identifierVersion, tx);
		dba.updateInstanceAttribute(stableIdentifierInst, ReactomeJavaConstants._displayName, tx);
		dba.updateInstanceAttribute(stableIdentifierInst, ReactomeJavaConstants.modified, tx);
	}

	// Checks via the 'releaseStatus', 'revised', and 'reviewed' attributes if this instance has been updated since last release.
	// Also goes through any child 'hasEvent' instances and recursively checks as well.
	private boolean isUpdated(GKInstance sliceInstance, GKInstance prevSliceInstance) throws Exception {
		if (!isEvent(sliceInstance)) {
			return false;
		}

		return hasReleaseStatus(sliceInstance) ||
			(recentlyRevised(sliceInstance, prevSliceInstance) || recentlyReviewed(sliceInstance, prevSliceInstance)) ||
			(isPathway(sliceInstance) && anyChildEventInstancesUpdated(sliceInstance));
	}

	private boolean isEvent(GKInstance sliceInstance) {
		return sliceInstance.getSchemClass().isa(ReactomeJavaConstants.Event);
	}

	private boolean hasReleaseStatus(GKInstance sliceInstance) throws Exception {
		return sliceInstance.getAttributeValue(ReactomeJavaConstants.releaseStatus) != null;
	}

	private boolean recentlyRevised(GKInstance sliceInstance, GKInstance prevSliceInstance) throws Exception {
		Collection<GKInstance> revisedInstances = sliceInstance.getAttributeValuesList(ReactomeJavaConstants.revised);
		Collection<GKInstance> prevRevisedInstances = prevSliceInstance.getAttributeValuesList(ReactomeJavaConstants.revised);

		if (revisedInstances.size() > prevRevisedInstances.size()) {
			return true;
		}
		return false;
	}

	private boolean recentlyReviewed(GKInstance sliceInstance, GKInstance prevSliceInstance) throws Exception {
		Collection<GKInstance> reviewedInstances = sliceInstance.getAttributeValuesList(ReactomeJavaConstants.reviewed);
		Collection<GKInstance> prevReviewedInstances = prevSliceInstance.getAttributeValuesList(ReactomeJavaConstants.reviewed);
		if (reviewedInstances.size() > prevReviewedInstances.size()) {
			return true;
		}
		return false;
	}

	private boolean isPathway(GKInstance sliceInstance) {
		return sliceInstance.getSchemClass().isValidAttribute(ReactomeJavaConstants.hasEvent);
	}

	private boolean anyChildEventInstancesUpdated(GKInstance sliceInstance) throws Exception {
		Collection<GKInstance> eventInstances = sliceInstance.getAttributeValuesList(ReactomeJavaConstants.hasEvent);
		for (GKInstance eventInst : eventInstances) {
			GKInstance prevEventInst = getDbaPrevSlice().fetchInstance(eventInst.getDBID());

			try {
				if (prevEventInst != null && isUpdated(eventInst, prevEventInst)) {
					return true;
				}
			} catch (Exception e) {
				logger.error("Unable to check if {} is updated", eventInst, e);
			}
		}
		return false;
	}

	private Neo4JAdaptor getDbaSlice() {
		return this.dbaSlice;
	}

	private Neo4JAdaptor getDbaPrevSlice() {
		return this.dbaPrevSlice;
	}

	private Neo4JAdaptor getDbaGKCentral() {
		return this.dbaGKCentral;
	}

	private long getPersonId() {
		return this.personId;
	}
}
