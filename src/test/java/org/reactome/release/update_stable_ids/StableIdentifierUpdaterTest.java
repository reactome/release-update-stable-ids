package org.reactome.release.update_stable_ids;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.reactome.release.common.database.InstanceEditUtils;
import org.powermock.modules.junit4.PowerMockRunner;
import org.reactome.server.service.model.GKInstance;
import org.reactome.server.service.persistence.Neo4JAdaptor;
import org.reactome.server.service.schema.SchemaClass;

import java.util.*;


@RunWith(PowerMockRunner.class)
@PrepareForTest({StableIdentifierUpdater.class, InstanceEditUtils.class})
@PowerMockIgnore({
	"org.apache.logging.log4j.*", "javax.management.*", "javax.script.*", "javax.xml.*", "com.sun.org.apache.xerces.*",
	"org.xml.sax.*", "com.sun.xml.*", "org.w3c.dom.*", "org.mockito.*"
})
public class StableIdentifierUpdaterTest {

	private Neo4JAdaptor mockSliceAdaptor = PowerMockito.mock(Neo4JAdaptor.class);
	private Neo4JAdaptor mockPrevSliceAdaptor = PowerMockito.mock(Neo4JAdaptor.class);
	private Neo4JAdaptor mockGkCentralAdaptor = PowerMockito.mock(Neo4JAdaptor.class);

	@Mock
	private GKInstance mockInstanceEdit;

	@Mock
	private GKInstance mockInstance;
	@Mock
	private GKInstance mockInstance2;
	@Mock
	private GKInstance mockInstance3;
	@Mock
	private GKInstance mockInstanceNull = null;

	@Mock
	private SchemaClass mockSchemaClass;

	private List<GKInstance> sliceList;
	private List<GKInstance> sliceList2;
	private List<GKInstance> sliceListNull;


	@Before
	public void setUp() {
		MockitoAnnotations.initMocks(this);
	}

	@Test
	public void updateTest() throws Exception {

		PowerMockito.mockStatic(InstanceEditUtils.class);

		sliceList = Arrays.asList(mockInstance, mockInstance, mockInstance);
		sliceList2 = Arrays.asList(mockInstance, mockInstance);

		Mockito.when(mockSliceAdaptor.fetchInstancesByClass("Event")).thenReturn(sliceList);
		Mockito.when(mockSliceAdaptor.fetchInstancesByClass("PhysicalEntity")).thenReturn(sliceList);

		Mockito.when(mockPrevSliceAdaptor.fetchInstance(mockInstance.getDBID())).thenReturn(mockInstance2);
		Mockito.when(mockGkCentralAdaptor.fetchInstance(mockInstance.getDBID())).thenReturn(mockInstance3);

		Mockito.when(mockInstance.getAttributeValuesList("modified")).thenReturn(sliceList);
		Mockito.when(mockInstance2.getAttributeValuesList("modified")).thenReturn(sliceList2);

		Mockito.when(mockInstance.getAttributeValue("stableIdentifier")).thenReturn(mockInstance);
		Mockito.when(mockInstance3.getAttributeValue("stableIdentifier")).thenReturn(mockInstance);

		Mockito.when(mockInstance.getAttributeValue("identifierVersion")).thenReturn("1");

		Mockito.when(mockInstance.getSchemClass()).thenReturn(mockSchemaClass);
		Mockito.when(mockInstance.getSchemClass().isa("Event")).thenReturn(true);

		Mockito.when(mockInstance.getAttributeValuesList("reviewed")).thenReturn(sliceList);
		Mockito.when(mockInstance2.getAttributeValuesList("reviewed")).thenReturn(sliceList2);

		StableIdentifierUpdater stableIdentifierUpdater =
			new StableIdentifierUpdater(mockSliceAdaptor, mockPrevSliceAdaptor, mockGkCentralAdaptor, 12345L);
		stableIdentifierUpdater.update();
	}

	@Test
	public void updateModifiedListsWithSameSizeTest() throws Exception {
		PowerMockito.mockStatic(InstanceEditUtils.class);

		sliceList = Arrays.asList(mockInstance);
		sliceList2 = Arrays.asList(mockInstance, mockInstance);

		Mockito.when(mockSliceAdaptor.fetchInstancesByClass("Event")).thenReturn(sliceList);
		Mockito.when(mockSliceAdaptor.fetchInstancesByClass("PhysicalEntity")).thenReturn(sliceList);

		Mockito.when(mockPrevSliceAdaptor.fetchInstance(mockInstance.getDBID())).thenReturn(mockInstance2);
		Mockito.when(mockGkCentralAdaptor.fetchInstance(mockInstance.getDBID())).thenReturn(mockInstance3);

		Mockito.when(mockInstance.getAttributeValuesList("modified")).thenReturn(sliceList);
		Mockito.when(mockInstance2.getAttributeValuesList("modified")).thenReturn(sliceList2);

		Mockito.when(mockInstance.getSchemClass()).thenReturn(mockSchemaClass);
		Mockito.when(mockInstance.getSchemClass().isa("Event")).thenReturn(false);

		StableIdentifierUpdater stableIdentifierUpdater =
			new StableIdentifierUpdater(mockSliceAdaptor, mockPrevSliceAdaptor, mockGkCentralAdaptor, 12345L);
		stableIdentifierUpdater.update();
	}
}