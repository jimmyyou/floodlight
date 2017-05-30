package net.floodlightcontroller.flowinstaller;

// Creates a socket and listens for FlowMod messages for baseline.
// In the future we could extend this to work with other schedulers

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionApplyActions;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructions;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.*;
import org.python.antlr.ast.Str;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

// Expected message input:
// [dpID] [dstIP] [outPort]
// dpID: ID of the switch
// dstIP: dst of this packet
// outPort: output port of this switch

// first metadata:
// num_of_switches  num_destination_nodes (including CTRL)


public class BaselineFlowModListener implements Runnable{
    protected IOFSwitchService switchService;
    protected ServerSocket socToGAIA;

    private Logger log = LoggerFactory.getLogger(BaselineFlowModListener.class);

    public BaselineFlowModListener(IOFSwitchService switchService) {
        switchService = switchService;
    }

    @Override
    public void run() {

        try {
            int port = 23456;
            log.info("BaselineFlowModListener listening on port " + port);
            socToGAIA = new ServerSocket(port);
            Socket soc = socToGAIA.accept();

            log.info("Socket to GAIA controller connected.");

            // receives the first message. num_of_switches,num_destination_nodes
            BufferedReader br = new BufferedReader(new InputStreamReader(soc.getInputStream()));

            String metadata;
            int numSw = 0, numNode = 0;
            if((metadata = br.readLine())!=null){
                String [] split = metadata.split(" ");
                assert (split.length != 2);
                numSw = Integer.parseInt(split[0]);
                numNode = Integer.parseInt(split[1]);
            }
            else {
                log.error("received null from GAIA");
                System.exit(1);
            }

            System.out.println("Expect to receive " + numSw + " * " + numNode + " = " + (numSw * numNode) + " messages" );

            // receives m * n messages, each containing routing for 1 nodes
            for (int i = 0 ; i < numSw ; i++ ){
                for (int j = 0 ; j < numNode ; j++ ){
                    String msg = br.readLine();
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
            DataOutputStream dos = new DataOutputStream(soc.getOutputStream());
            dos.write('1'); // this is the ack
            dos.flush();

            log.info("Sent ACK back to GAIA");

        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
