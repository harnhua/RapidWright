/*
 * Copyright (c) 2023-2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetTools;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;
import com.xilinx.rapidwright.util.VivadoTools;
import com.xilinx.rapidwright.util.VivadoToolsHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TestGlobalSignalRouting {
    @ParameterizedTest
    @CsvSource({
            "CLKBWRCLK,",
            "RSTRAMB,",
            "WEBWE[0],WEBL0",
            "ADDRENA,ADDRENAL",
            "ADDRENB,ADDRENBU"
    })
    public void testRAMB36(String logicalPinName, String erroringSitePinName) throws Throwable {
        Design design = new Design("design", "xcvu3p");
        Cell bufg = design.createAndPlaceCell("test_bufg", Unisim.BUFGCE, "BUFGCE_X0Y0/BUFCE");
        Net globalNet = design.createNet("clk");
        globalNet.connect(bufg,"O");

        Cell target = design.createAndPlaceCell("test_ram", Unisim.RAMB36E2, "RAMB36_X0Y0/RAMB36E2");
        if (logicalPinName.equals("CLKBWRCLK") || logicalPinName.equals("RSTRAMB") ||
                logicalPinName.equals("ADDRENA") || logicalPinName.equals("ADDRENB")) {
            target.addPinMapping(logicalPinName + "L", logicalPinName);
            target.addPinMapping(logicalPinName + "U", logicalPinName);
        } else if (logicalPinName.equals("WEBWE[0]")) {
            target.addPinMapping("WEBWEL0", logicalPinName);
            target.addPinMapping("WEBWEU0", logicalPinName);
        }
        globalNet.connect(target, logicalPinName);

        // FIXME: Currently, Net.connect() only connects the first physical pin to the net
        //        This is a canary assertion that will light up when this gets fixed.
        Assertions.assertEquals(2 /* 3 */, globalNet.getPins().size());

        Executable e = () -> GlobalSignalRouting.symmetricClkRouting(globalNet, design.getDevice(), (n) -> NodeStatus.AVAILABLE);
        if (erroringSitePinName == null) {
            e.execute();
        } else {
            // FIXME: Known broken -- see https://github.com/Xilinx/RapidWright/issues/756
            RuntimeException ex = Assertions.assertThrows(RuntimeException.class, e, "true");
            Assertions.assertEquals("ERROR: No mapped LCB to SitePinInst IN RAMB36_X0Y0." + erroringSitePinName,
                    ex.getMessage());
        }
    }

    // NOTE: This method does not avoid any existing routing (static or signal),
    //       only their pins
    NodeStatus getNodeState(Design design, NetType netType, Node n) {
        SitePin sitePin = n.getSitePin();
        SiteInst site = (sitePin != null) ? design.getSiteInstFromSite(sitePin.getSite()) : null;
        SitePinInst spi = (site != null) ? site.getSitePinInst(sitePin.getPinName()) : null;
        Net net = (spi != null) ? spi.getNet() : null;
        return net == null ? NodeStatus.AVAILABLE :
                net.getType() == netType ? NodeStatus.INUSE : NodeStatus.UNAVAILABLE;
    }

    @Test
    public void testRouteStaticNet() {
        Design design = RapidWrightDCP.loadDCP("optical-flow.dcp");

        RWRoute.preprocess(design);

        Net gndNet = design.getGndNet();
        Net vccNet = design.getVccNet();
        Assertions.assertFalse(gndNet.hasPIPs());
        Assertions.assertFalse(vccNet.hasPIPs());

        List<SitePinInst> gndPins = gndNet.getPins();
        List<SitePinInst> vccPins = vccNet.getPins();

        boolean invertLutInputs = true;
        RouterHelper.invertPossibleGndPinsToVccPins(design, gndPins, invertLutInputs);

        Assertions.assertEquals(19010, gndPins.size());
        Assertions.assertEquals(23099, vccPins.size());

        RouteThruHelper routeThruHelper = new RouteThruHelper(design.getDevice());

        GlobalSignalRouting.routeStaticNet(gndPins, (n) -> getNodeState(design, NetType.GND, n), design, routeThruHelper);
        gndPins = gndNet.getPins();
        Assertions.assertEquals(737, gndPins.stream().filter((spi) -> spi.isOutPin()).count());
        Assertions.assertEquals(19010, gndPins.stream().filter((spi) -> !spi.isOutPin()).count());
        Assertions.assertEquals(33429, gndNet.getPIPs().size());

        GlobalSignalRouting.routeStaticNet(vccPins, (n) -> getNodeState(design, NetType.VCC, n), design, routeThruHelper);
        vccPins = vccNet.getPins();
        Assertions.assertEquals(0, vccPins.stream().filter((spi) -> spi.isOutPin()).count());
        Assertions.assertEquals(23099, vccPins.stream().filter((spi) -> !spi.isOutPin()).count());
        Assertions.assertEquals(27491, vccNet.getPIPs().size());

        if (FileTools.isVivadoOnPath()) {
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(2, rrs.fullyRoutedNets);
            Assertions.assertEquals(0, rrs.netsWithRoutingErrors);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    public void testRouteStaticNetOnVersalDevice(boolean createStaticPins, @TempDir Path tempDir) {
        Design design = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        design.unrouteDesign();

        Net gndNet = design.getGndNet();
        Net vccNet = design.getVccNet();
        Assertions.assertFalse(gndNet.hasPIPs());
        Assertions.assertFalse(vccNet.hasPIPs());

        List<SitePinInst> gndPins;
        List<SitePinInst> vccPins;

        if (createStaticPins) {
            // Simulate a DCP with no static routing (and thus no static sink pins)
            // by refreshing the unrouted design
            Path tempCheckpoint = tempDir.resolve("checkpoint.dcp");
            design.writeCheckpoint(tempCheckpoint);
            design = Design.readCheckpoint(tempCheckpoint);

            gndNet = design.getGndNet();
            vccNet = design.getVccNet();
            gndPins = gndNet.getPins();
            vccPins = vccNet.getPins();

            Assertions.assertEquals(0, vccPins.size());
            Assertions.assertEquals(0, gndPins.size());

            DesignTools.createMissingSitePinInsts(design, gndNet);
            DesignTools.createMissingSitePinInsts(design, vccNet);

            Assertions.assertEquals(140, gndPins.size());
            Assertions.assertEquals(165, vccPins.size());
        } else {
            gndPins = gndNet.getPins();
            vccPins = vccNet.getPins();

            // Remove all existing output pins so that we can count how many new ones were created
            for (SitePinInst spi : new ArrayList<>(gndPins)) {
                if (spi.isOutPin()) {
                    gndNet.removePin(spi);
                    spi.detachSiteInst();
                }
            }
            for (SitePinInst spi : new ArrayList<>(vccPins)) {
                if (spi.isOutPin()) {
                    vccNet.removePin(spi);
                    spi.detachSiteInst();
                }
            }

            Assertions.assertEquals(123, gndPins.size());
            Assertions.assertEquals(230, vccPins.size());
        }

        // Even though we may be starting from a fully-routed design, Versal designs still need
        // some preprocessing to discover all SLICE.CE pins
        DesignTools.createPossiblePinsToStaticNets(design);

        Assertions.assertEquals(123, gndPins.size());
        Assertions.assertEquals(232, vccPins.size());

        RouteThruHelper routeThruHelper = new RouteThruHelper(design.getDevice());

        Design finalDesign = design;
        GlobalSignalRouting.routeStaticNet(gndPins, (n) -> getNodeState(finalDesign, NetType.GND, n), design, routeThruHelper);
        Assertions.assertEquals(8, gndPins.stream().filter((spi) -> spi.isOutPin()).count());
        Assertions.assertEquals(123, gndPins.stream().filter((spi) -> !spi.isOutPin()).count());
        Assertions.assertEquals(436, gndNet.getPIPs().size());

        GlobalSignalRouting.routeStaticNet(vccPins, (n) -> getNodeState(finalDesign, NetType.VCC, n), design, routeThruHelper);
        Assertions.assertEquals(0, vccPins.stream().filter((spi) -> spi.isOutPin()).count());
        Assertions.assertEquals(232, vccPins.stream().filter((spi) -> !spi.isOutPin()).count());
        Assertions.assertEquals(464, vccNet.getPIPs().size());

        if (FileTools.isVivadoOnPath()) {
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(2, rrs.fullyRoutedNets);
            Assertions.assertEquals(0, rrs.netsWithRoutingErrors);
        }
    }

    // This is a minimized testcase from the result of GlobalSignalRouter
    @ParameterizedTest
    @CsvSource({
            "false,false",
            "true,false",
            "false,true"
    })
    public void testMuxOutPinAsStaticSourceEvenWithLut6(boolean setCmuxCtag, boolean fullIntraSiteRouting) {
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());

        Design design = RapidWrightDCP.loadDCP("optical-flow.dcp");
        Device device = design.getDevice();
        Net gndNet = design.getGndNet();
        boolean srcToSinkOrder = true;
        gndNet.setPIPs(RouterHelper.getPIPsFromNodes(Arrays.asList(
                device.getNode("CLEM_X52Y123/CLE_CLE_M_SITE_0_CMUX"),
                device.getNode("INT_X52Y123/INT_NODE_SDQ_90_INT_OUT1"),
                device.getNode("INT_X52Y123/WW1_W_BEG7"),
                device.getNode("INT_X51Y124/INODE_E_1_FT1"),
                device.getNode("INT_X51Y123/IMUX_E15")
        ), srcToSinkOrder));
        SiteInst si = design.getSiteInstFromSiteName("SLICE_X81Y123");
        Cell lut6 = si.getCell("C6LUT");
        Assertions.assertEquals("LUT6", lut6.getType());
        String const0 = "<const0>";
        if (setCmuxCtag) {
            if (!fullIntraSiteRouting) {
                // Setting just the CMUX cTag
                si.routeIntraSiteNet(gndNet, si.getBELPin("CMUX", "CMUX"), si.getBELPin("CMUX", "CMUX"));
            } else {
                // Full O5 -> OUTMUXC -> CMUX intra-site routing
                si.routeIntraSiteNet(gndNet, si.getBELPin("C5LUT", "O5"), si.getBELPin("CMUX", "CMUX"));
            }
        }

        String status = VivadoTools.reportRouteStatus(design, const0);
        if (!setCmuxCtag) {
            // Not setting the CMUX cTag correctly causes SLICE_X81Y123 to have invalid site programming
            Assertions.assertEquals("CONFLICTS", status);
        } else {
            // In both cases, SLICE_X81Y123 does not exhibit invalid site programming and thus net appears partially routed
            Assertions.assertEquals("PARTIAL", status);
        }
    }

    // This is a minimized testcase from the result of GlobalSignalRouter
    @ParameterizedTest
    @CsvSource({
            "false,false",
            "true,false",
            "false,true"
    })
    public void testMuxOutPinAsStaticSourceEvenWithLutRam(boolean setFmuxCtag, boolean fullIntraSiteRouting) {
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());

        Design design = RapidWrightDCP.loadDCP("bnn.dcp");
        Device device = design.getDevice();
        Net gndNet = design.getGndNet();
        boolean srcToSinkOrder = true;
        gndNet.setPIPs(RouterHelper.getPIPsFromNodes(Arrays.asList(
                device.getNode("CLEM_X52Y218/CLE_CLE_M_SITE_0_FMUX"),
                device.getNode("INT_X52Y218/SDQNODE_W_2_FT1"),
                device.getNode("INT_X52Y218/EE2_W_BEG0"),
                device.getNode("INT_X53Y218/INODE_W_1_FT1"),
                device.getNode("INT_X53Y217/IMUX_W46"),
                device.getNode("BRAM_X53Y215/BRAM_BRAM_CORE_3_ADDRENAU_PIN")
        ), srcToSinkOrder));
        SiteInst si = design.getSiteInstFromSiteName("SLICE_X81Y218");
        Cell lutRam = si.getCell("F6LUT");
        Assertions.assertEquals("RAMS64E", lutRam.getType());
        String const0 = "bd_0_i/hls_inst/inst/<const0>";
        if (setFmuxCtag) {
            if (!fullIntraSiteRouting) {
                // Setting just the FMUX cTag
                si.routeIntraSiteNet(gndNet, si.getBELPin("FMUX", "FMUX"), si.getBELPin("FMUX", "FMUX"));
            } else {
                // Full O5 -> OUTMUXF -> FMUX intra-site routing
                si.routeIntraSiteNet(gndNet, si.getBELPin("F5LUT", "O5"), si.getBELPin("FMUX", "FMUX"));
            }
        }
        String status = VivadoTools.reportRouteStatus(design, const0);
        if (!setFmuxCtag) {
            // Not setting the FMUX cTag correctly causes SLICE_X81Y218 to have invalid site programming
            Assertions.assertEquals("CONFLICTS", status);
        } else {
            // In both cases, SLICE_X81Y218 does not exhibit invalid site programming and thus net appears partially routed
            Assertions.assertEquals("PARTIAL", status);
        }
    }

    @Test
    public void testSymmetricClkRouting() {
        Design design = RapidWrightDCP.loadDCP("two_clk_check_NetTools.dcp");
        design.unrouteDesign();
        // Simulate the preserve method
        Set<Node> used = new HashSet<>();

        for (String netName : Arrays.asList("clk1_IBUF_BUFG", "clk2_IBUF_BUFG", "rst1", "rst2")) {
            Net net = design.getNet(netName);
            Assertions.assertTrue(NetTools.isGlobalClock(net));
            GlobalSignalRouting.symmetricClkRouting(net, design.getDevice(), (n) -> used.contains(n) ? NodeStatus.UNAVAILABLE : NodeStatus.AVAILABLE);
            for (PIP pip: net.getPIPs()) {
                for (Node node: Arrays.asList(pip.getStartNode(), pip.getEndNode())) {
                    if (node != null) used.add(node);
                }
            }
            Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(net));
            for (SitePinInst spi : net.getPins()) {
                Assertions.assertTrue(spi.isRouted());
            }
        }

        if (FileTools.isVivadoOnPath()) {
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(4, rrs.fullyRoutedNets);
            Assertions.assertEquals(0, rrs.netsWithRoutingErrors);
        } else {
            System.err.println("WARNING: vivado not on PATH");
        }
    }
}
