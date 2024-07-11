package org.reactome.release.update_stable_ids;

import java.sql.SQLException;
import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.persistence.TransactionsNotSupportedException;
import org.reactome.release.common.database.InstanceEditUtils;

public class StableIdentifierUpdater {

	private static final Logger logger = LogManager.getLogger();

	private MySQLAdaptor dbaSlice;
	private MySQLAdaptor dbaPrevSlice;
	private MySQLAdaptor dbaGKCentral;
	private long personId;

	private GKInstance sliceInstanceEdit;
	private GKInstance gkCentralInstanceEdit;

	public StableIdentifierUpdater(
		MySQLAdaptor dbaSlice, MySQLAdaptor dbaPrevSlice, MySQLAdaptor dbaGkCentral, long personId) {

		this.dbaSlice = dbaSlice;
		this.dbaPrevSlice = dbaPrevSlice;
		this.dbaGKCentral = dbaGkCentral;
		this.personId = personId;
	}

	@SuppressWarnings("unchecked")
	public void update() throws Exception {
		startTransaction();
		//TODO: Perl wrapper will create a 'snapshot' of the previous slice -- once the wrapper is retired this needs to be done

		int incrementedCount = 0;
		int notIncrementedCount = 0;

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

			// Compare number of 'Modified' instances between slices
			Collection<GKInstance> sliceInstanceModified = sliceInstance.getAttributeValuesList(ReactomeJavaConstants.modified);
			Collection<GKInstance> prevSliceInstanceModified = prevSliceInstance.getAttributeValuesList(ReactomeJavaConstants.modified);

			if (sliceInstanceModified.size() < prevSliceInstanceModified.size()) {
				String errorMessage =
					sliceInstance + " in current release has less modification instances than previous release";
				logger.fatal(errorMessage);
				throw new IllegalStateException(errorMessage);
			}

			if (sliceInstanceModified.size() > prevSliceInstanceModified.size()) {
				boolean incrementSuccessful = attemptIncrementOfStableId(sliceInstance, gkCentralInstance, prevSliceInstance);
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
						sliceInstance.addAttributeValue(ReactomeJavaConstants.modified, getSliceInstanceEdit());
						getDbaSlice().updateInstanceAttribute(sliceInstance, ReactomeJavaConstants.releaseStatus);
						getDbaSlice().updateInstanceAttribute(sliceInstance, ReactomeJavaConstants.modified);
					} else {
						logger.info("StableIdentifier has already been updated during this release");
					}
				}
			} catch (Exception e) {
				logger.error("Unable to check if {} was updated", sliceInstance, e);
			}
		}
		commit();

		logger.info(incrementedCount + " Stable Identifiers were updated");
		logger.info(notIncrementedCount + " were not updated");
		logger.info("UpdateStableIdentifiers step has finished");
	}

	private GKInstance getSliceInstanceEdit() throws Exception {
		if (this.sliceInstanceEdit == null) {
			this.sliceInstanceEdit = getInstanceEdit(getDbaSlice());
		}

		return this.sliceInstanceEdit;
	}

	private GKInstance getGkCentralInstanceEdit() throws Exception {
		if (this.gkCentralInstanceEdit == null) {
			this.gkCentralInstanceEdit = getInstanceEdit(getDbaGKCentral());
		}

		return this.gkCentralInstanceEdit;
	}

	private GKInstance getInstanceEdit(MySQLAdaptor dbAdaptor) throws Exception {
		final String creatorName = "org.reactome.release.updateStableIds";
		return InstanceEditUtils.createInstanceEdit(dbAdaptor, getPersonId(), creatorName);
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

	private boolean attemptIncrementOfStableId(GKInstance sliceInstance, GKInstance gkCentralInstance, GKInstance prevSliceInstance) throws Exception {
		// Make sure StableIdentifier instance exists
		if (sliceInstance.getAttributeValue(ReactomeJavaConstants.stableIdentifier) != null && gkCentralInstance.getAttributeValue(ReactomeJavaConstants.stableIdentifier) != null) {
			logger.info("\tIncrementing " + sliceInstance.getAttributeValue(ReactomeJavaConstants.stableIdentifier));
			incrementStableIdentifier(sliceInstance, getDbaSlice(), getSliceInstanceEdit());
			incrementStableIdentifier(gkCentralInstance, getDbaGKCentral(), getGkCentralInstanceEdit());
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
	private void incrementStableIdentifier(GKInstance instance, MySQLAdaptor dba, GKInstance instanceEdit) throws Exception {
		GKInstance stableIdentifierInst = (GKInstance) instance.getAttributeValue(ReactomeJavaConstants.stableIdentifier);
		String id = (String) stableIdentifierInst.getAttributeValue(ReactomeJavaConstants.identifier);
		int idVersion = Integer.parseInt((String) stableIdentifierInst.getAttributeValue(ReactomeJavaConstants.identifierVersion));
		int newIdentifierVersion = idVersion + 1;

		stableIdentifierInst.addAttributeValue(ReactomeJavaConstants.identifierVersion, String.valueOf(newIdentifierVersion));
		stableIdentifierInst.setDisplayName(id + "." + newIdentifierVersion);
		Collection<GKInstance> modifiedInstances = (Collection<GKInstance>) stableIdentifierInst.getAttributeValuesList(ReactomeJavaConstants.modified);
		stableIdentifierInst.addAttributeValue(ReactomeJavaConstants.modified, instanceEdit);
		dba.updateInstanceAttribute(stableIdentifierInst, ReactomeJavaConstants.identifierVersion);
		dba.updateInstanceAttribute(stableIdentifierInst, ReactomeJavaConstants._displayName);
		dba.updateInstanceAttribute(stableIdentifierInst, ReactomeJavaConstants.modified);
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

	private void startTransaction() throws SQLException, TransactionsNotSupportedException {
		// At time of writing (December 2018), test_slice is a non-transactional database.
		// This check has been put in place as a safety net in case that changes.
		if (getDbaSlice().supportsTransactions()) {
			getDbaSlice().startTransaction();
		}
		getDbaGKCentral().startTransaction();
	}

	private void commit() throws SQLException {
		// TODO: Update test_slice after gkCentral has been successfully updated
		if (getDbaSlice().supportsTransactions()) {
			getDbaSlice().commit();
		}
		logger.info("Commiting all changes in " + getDbaGKCentral().getDBName());
		getDbaGKCentral().commit();
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

	private MySQLAdaptor getDbaSlice() {
		return this.dbaSlice;
	}

	private MySQLAdaptor getDbaPrevSlice() {
		return this.dbaPrevSlice;
	}

	private MySQLAdaptor getDbaGKCentral() {
		return this.dbaGKCentral;
	}

	private long getPersonId() {
		return this.personId;
	}
}
