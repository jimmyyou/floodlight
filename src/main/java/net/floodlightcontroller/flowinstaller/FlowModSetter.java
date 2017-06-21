package net.floodlightcontroller.flowinstaller;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;

import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructions;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionApplyActions;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TransportPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Ver 1.1 moved the functionality of Baseline Flow Mod Setting to here
// the first message: [num_needed] for rrf , [num_Sw] [num_Node] for baseline

public class FlowModSetter implements Runnable {
    protected IOFSwitchService switchService;
    private Logger log = LoggerFactory.getLogger(FlowModSetter.class);

    public FlowModSetter(IOFSwitchService switchService) {
        this.switchService = switchService;
    }
    BufferedReader br = null;

    public void run() {
        String buf_str = null;
        // Set up the fifo we'll receive from
        try {
            Runtime rt = Runtime.getRuntime();
            String recv_fifo_name = "/tmp/gaia_fifo_to_of";
            rt.exec("rm " +  recv_fifo_name).waitFor();
            rt.exec("mkfifo " + recv_fifo_name).waitFor();
            File f = new File(recv_fifo_name);
            FileReader fr = new FileReader(f);
            br = new BufferedReader(fr);

            // The first message from the GAIA controller contains
            // the number of messages we will receive (the number of
            // unique msg_ids that we'll receive).
            buf_str = br.readLine();
        }
        catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        String [] split = buf_str.split(" ");
        if(split.length == 1){
            int num_needed = Integer.parseInt(buf_str);
            System.out.println("Enter gaia mode:\n need " + num_needed);
            parseCoflowRules(num_needed);
        }
        else if (split.length == 2){
            int numSw = Integer.parseInt(split[0]);
            int numNode = Integer.parseInt(split[1]);
            System.out.println("Enter baseline mode:");
            parseBaselineRules(numSw , numNode);
        }
        else {
            log.error("Received unexpected first message: " + buf_str);
        }

    }

    private void parseBaselineRules(int numSw, int numNode) {
        System.out.println("Expect to receive " + numSw + " * " + numNode + " = " + (numSw * numNode) + " messages" );

        // receives m * n messages, each containing routing for 1 nodes
        for (int i = 0 ; i < numSw ; i++ ){
            for (int j = 0 ; j < numNode ; j++ ){

                String msg = null;
                try {
                    msg = br.readLine();
                } catch (IOException e) {
                    e.printStackTrace();
                    System.exit(1);
                }

                System.out.println(msg);

                String [] split = msg.split(" ");
                assert (split.length != 3);
                int dpID = Integer.parseInt(split[0]);
                String dstIP = split[1];
                int outPort = Integer.parseInt(split[2]);

                // Set the rules
                IOFSwitch sw = switchService.getSwitch(DatapathId.of(dpID));

                // Set the match for the FlowMod based on the info we received
                // Match IPv4, TCP, dst_IP, don't match the port
                Match m = sw.getOFFactory().buildMatch()
                        .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                        .setExact(MatchField.IPV4_DST, IPv4Address.of(dstIP))
                        .setExact(MatchField.IP_PROTO, IpProtocol.TCP)
                        .build();

                // Set the FlowMod's action to forward packets through out_port
                OFActionOutput.Builder aob = sw.getOFFactory().actions().buildOutput();
                aob.setPort(OFPort.of(outPort));
                List<OFAction> actions = new ArrayList<OFAction>();
                actions.add(aob.build());

                OFInstructions ib = sw.getOFFactory().instructions();
                OFInstructionApplyActions applyActions = ib.buildApplyActions()
                        .setActions(actions)
                        .build();
                List<OFInstruction> instructions = new ArrayList<OFInstruction>();
                instructions.add(applyActions);

                // Use priority 100?
                OFFlowAdd fm = sw.getOFFactory().buildFlowAdd()
                        .setMatch(m)
                        .setPriority(100)
                        .setOutPort(OFPort.of(outPort))
                        .setInstructions(instructions)
                        .build();

                sw.write(fm);

            } // end of n messages for one switch
        } // end of all m*n messages

        // Send back ACK after setting up the rules
        replyACK();
        log.info("Sent ACK back to GAIA");
    }

    private void parseCoflowRules(int num_needed) {
        String buf_str = null;

        int num_recv = 0;
        String msg_id, mod_src_ip, mod_dst_ip, src_ip_suffix, dst_ip_suffix;
        int num_rules, src_port, dst_port, dpid, out_port, mod_src_port, mod_dst_port;
        boolean forward;
        while (num_recv < num_needed) {
            // Receive a metadata message from the GAIA controller
            // Metadata is of form:
            //      msg_id num_rules src_id dst_id src_port dst_port
            //
            // msg_id:      used to keep track of how many rules the OF controller will set
            // num_rules:   how many rules will be set for this msg_id
            // src_id:      id of path source
            // dst_id:      id of path destination
            // src_port:    port number used by sending agent
            // dst_port:    port number used by receiving agent
            try {
                buf_str = br.readLine();
            }
            catch (java.io.IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
            String[] splits = buf_str.split(" ");
            if (splits.length != 6) {
                System.out.println("ERROR: Expected a metadata announcement but received a rule instead");
                System.out.println("Received: " + buf_str);
                System.exit(1);
            }

            msg_id = splits[0];
            num_rules = Integer.parseInt(splits[1]);
            src_ip_suffix = splits[2];
            dst_ip_suffix = splits[3];
            src_port = Integer.parseInt(splits[4]);
            dst_port = Integer.parseInt(splits[5]);
            System.out.println(buf_str);

            // Receive each of the rules for this path message
            // Individual messages are of form:
            //      msg_id dpid out_port fwd_or_rev
            //
            // dpid:        id of switch to be programmed
            // out_port:    interface through which packets should be forwarded
            // fwd_or_rev:  0 means this rule is for the forward direction,
            //              1 means for the reverse direction
            //              If on reverse direction, src_{id, port} should be
            //              switched with dst_{ip, port}.
            for (int i = 0; i < num_rules; i++) {
                try {
                    buf_str = br.readLine();
                }
                catch (java.io.IOException e) {
                    e.printStackTrace();
                    System.exit(1);
                }

                String[] msg_splits = buf_str.split(" ");
                if (msg_splits.length != 4) {
                    System.out.println("ERROR: Expected a rule but received metadata instead");
                    System.out.println("Received: " + buf_str);
                    System.exit(1);
                }
                else if (!msg_id.equals(msg_splits[0])) {
                    System.out.println("ERROR: Non matching msg_ids. Received " + msg_splits[0] + " but expected " + msg_id);
                    System.exit(1);
                }

                dpid = Integer.parseInt(msg_splits[1]);
                out_port = Integer.parseInt(msg_splits[2]);
                forward = msg_splits[3].equals("0");

                System.out.println(buf_str);

                // If this rule is for the reverse path (TCP ACKs),
                // switch the src and dst values.
                if (forward) {
                    mod_src_ip = "10.0.0." + src_ip_suffix;
                    mod_dst_ip = "10.0.0." + dst_ip_suffix;
                    mod_src_port = src_port;
                    mod_dst_port = dst_port;
                }
                else {
                    mod_src_ip = "10.0.0." + dst_ip_suffix;
                    mod_dst_ip = "10.0.0." + src_ip_suffix;
                    mod_src_port = dst_port;
                    mod_dst_port = src_port;
                }

                IOFSwitch sw = switchService.getSwitch(DatapathId.of(dpid));

                // Set the match for the FlowMod based on the info we received
                Match m = sw.getOFFactory().buildMatch()
                        .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                        .setExact(MatchField.IPV4_SRC, IPv4Address.of(mod_src_ip))
                        .setExact(MatchField.IPV4_DST, IPv4Address.of(mod_dst_ip))
                        .setExact(MatchField.IP_PROTO, IpProtocol.TCP)
                        .setExact(MatchField.TCP_SRC, TransportPort.of(mod_src_port))
                        .setExact(MatchField.TCP_DST, TransportPort.of(mod_dst_port))
                        .build();

                // Set the FlowMod's action to forward packets through out_port
                OFActionOutput.Builder aob = sw.getOFFactory().actions().buildOutput();
                aob.setPort(OFPort.of(out_port));
                List<OFAction> actions = new ArrayList<OFAction>();
                actions.add(aob.build());

                OFInstructions ib = sw.getOFFactory().instructions();
                OFInstructionApplyActions applyActions = ib.buildApplyActions()
                        .setActions(actions)
                        .build();
                List<OFInstruction> instructions = new ArrayList<OFInstruction>();
                instructions.add(applyActions);

                OFFlowAdd fm = sw.getOFFactory().buildFlowAdd()
                        .setMatch(m)
                        .setPriority(100)
                        .setOutPort(OFPort.of(out_port))
                        .setInstructions(instructions)
                        .build();

                sw.write(fm);
            }

            num_recv++;
        } // end of receiving messages

        replyACK();
    }

    private void replyACK(){
        // Set up the fifo that we'll write to
        try {
            br.close();

            String send_fifo_name = "/tmp/gaia_fifo_to_ctrl";
            File f = new File(send_fifo_name);
            FileWriter fw = new FileWriter(f);

            // Notify the GAIA controller that we're done setting FlowMods
            fw.write("1");
            fw.close();
        }
        catch (java.io.IOException e ) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
