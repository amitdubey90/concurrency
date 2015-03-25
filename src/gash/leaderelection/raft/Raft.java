package gash.leaderelection.raft;

import gash.messaging.Message;
import gash.messaging.Message.Delivery;
import gash.messaging.Node;
import gash.messaging.transports.Bus;

import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Raft consensus algorithm is similar to PAXOS and Flood Max though it
 * claims to be easier to understand. The paper "In Search of an Understandable
 * Consensus Algorithm" explains the concept. See
 * https://ramcloud.stanford.edu/raft.pdf
 * 
 * Note the Raft algo is both a leader election and consensus algo. It ties the
 * election process to the state of its distributed log (state) as the state is
 * part of the decision process of which node should be the leader.
 * 
 * 
 * @author gash
 *
 */
public class Raft {
	static AtomicInteger msgID = new AtomicInteger(0);

	private Bus<? extends RaftMessage> transport;

	public Raft() {
		transport = new Bus<RaftMessage>(0);
	}

	public void addNode(RaftNode node) {
		if (node == null)
			return;

		node.setTransport(transport);

		@SuppressWarnings({ "rawtypes", "unchecked" })
		Node<Message> n = (Node) (node);
		transport.addNode(n);

		if (!node.isAlive())
			node.start();
	}

	/** processes heartbeats */
	public interface HeartMonitorListener {
		public void doMonitor();
	}

	public static abstract class LogEntryBase {
		private int term;
	}

	private static class LogEntry extends LogEntryBase {

	}

	/** triggers monitoring of the heartbeat */
	public static class RaftMonitor extends TimerTask {
		private RaftNode<RaftMessage> node;

		public RaftMonitor(RaftNode<RaftMessage> node) {
			if (node == null)
				throw new RuntimeException("Missing node");

			this.node = node;
		}

		@Override
		public void run() {
			node.checkBeats();
		}
	}

	/** our network node */
	public static class RaftNode<M extends RaftMessage> extends Node<M> {
		public enum RState {
			Follower, Candidate, Leader
		}

		private int electionTimeOut;
		// private int currentTimer;
		private long lastKnownBeat;
		private RState currentState;
		private int term;
		private RaftMessage votedFor;
		private int voteCount;
		private int adjacentNodes;
		private RaftMonitor monitor;
		private Timer timer;
		private int leaderID;

		private Bus<? extends RaftMessage> transport;

		public RaftNode(int id, int adjacentNodes) {
			super(id);

			this.adjacentNodes = adjacentNodes;
			this.electionTimeOut = new Random().nextInt(10000);
			if (this.electionTimeOut < 5000)
				this.electionTimeOut += 3000;
//
//			if (id == 1 || id == 2)
//				electionTimeOut = 2500;
			// currentTimer = electionTimeOut;
			currentState = RState.Follower;
		}

		private void sendLeaderNotice() {
			RaftMessage msg = new RaftMessage(Raft.msgID.incrementAndGet());
			msg.setOriginator(getNodeId());
			msg.setDeliverAs(Delivery.Broadcast);
			msg.setDestination(-1);
			msg.setAction(RaftMessage.Action.Leader);
			msg.setTerm(term);
			send(msg);
		}

		private void sendAppendNotice() {
			RaftMessage msg = new RaftMessage(Raft.msgID.incrementAndGet());
			msg.setOriginator(getNodeId());
			msg.setDeliverAs(Delivery.Broadcast);
			msg.setDestination(-1);
			msg.setAction(RaftMessage.Action.Append);
			msg.setTerm(term);
			send(msg);
		}

		/** TODO args should set voting preference */
		private void sendRequestVoteNotice() {
			RaftMessage msg = new RaftMessage(Raft.msgID.incrementAndGet());
			msg.setOriginator(getNodeId());
			msg.setDeliverAs(Delivery.Broadcast);
			msg.setDestination(-1);
			msg.setAction(RaftMessage.Action.RequestVote);
			msg.setTerm(term);
			send(msg);
		}

		private void send(RaftMessage msg) {
			// enqueue the message - if we directly call the nodes method, we
			// end up with a deep call stack and not a message-based model.
			transport.sendMessage(msg);
		}

		/** Changes made by Amit Dubey **/

		@SuppressWarnings("unchecked")
		@Override
		public void start() {
			if (this.timer != null)
				return;

			monitor = new RaftMonitor((RaftNode<RaftMessage>) this);
			timer = new Timer(true);
			int frequency = (int) (electionTimeOut * 0.75);
			if (frequency == 0)
				frequency = 1;

			timer.scheduleAtFixedRate(monitor, electionTimeOut * 2, frequency);
			super.start();
		}

		protected void checkBeats() {
			// System.out.println("--> node " + getNodeId() + " heartbeat");
			if (currentState == RState.Leader) {
				sendAppendNotice();
			} else /*
					 * if (currentState == RState.Candidate || currentState ==
					 * RState.Follower)
					 */{
				long now = System.currentTimeMillis();
				if (now - lastKnownBeat > electionTimeOut)
					startElection();
			}

		}

		private void startElection() {
			System.out.println("Timeout! Election declared by node "
					+ getNodeId()+ "for term "+ (term+1));

			// Declare itself candidate, vote for self and Broadcast request for
			// votes
			currentState = RState.Candidate;
			voteCount = 1;
			term++;
			// electionTimeOut = new Random().nextInt(10);
			// currentTimer = electionTimeOut;
			sendRequestVoteNotice();
		}

		private void voteForCandidate(RaftMessage voteRequest) {

			RaftMessage msg = new RaftMessage(Raft.msgID.incrementAndGet());
			msg.setOriginator(getNodeId());
			msg.setDeliverAs(Delivery.Direct);
			msg.setDestination(voteRequest.getOriginator());
			msg.setTerm(term);
			msg.setAction(RaftMessage.Action.Vote);

			//votedFor = msg;
			send(msg);
		}

		public void receiveVote(RaftMessage msg) {
			System.out.println("Vote received at node " + getNodeId()
					+ " votecount " + voteCount);
			if (++voteCount > (adjacentNodes / 2)) {
				voteCount = 0;
				sendLeaderNotice();
				currentState = RState.Leader;
				leaderID = getNodeId();
				System.out.println(" Leader elected "
						+ getNodeId());
			}
		}

		public void processReceivedMessage(RaftMessage msg) {

			RaftMessage.Action action = msg.getAction();

			/*
			 * switch (currentState) { case Leader:
			 * 
			 * if (msg.getTerm() >= this.term) { currentState = RState.Follower;
			 * // currentTimer = electionTimeOut; leaderID =
			 * msg.getOriginator(); }
			 * 
			 * break;
			 * 
			 * case Follower: if (action == RaftMessage.Action.Append) { term =
			 * msg.getTerm(); // do append work } else if (action ==
			 * RaftMessage.Action.Leader) { leaderID = msg.getOriginator(); }
			 * else if (action == RaftMessage.Action.RequestVote) {
			 * 
			 * 
			 * } else if (action == RaftMessage.Action.Vote) { // this is weird.
			 * Should not happen! } break;
			 * 
			 * case Candidate: if (action == RaftMessage.Action.Append) { if
			 * (msg.getTerm() >= this.term) { this.term = msg.getTerm();
			 * currentState = RState.Follower; } } else if (action ==
			 * RaftMessage.Action.Leader) { if (msg.getTerm() >= this.term) {
			 * this.term = msg.getTerm(); currentState = RState.Follower; } }
			 * else if (action == RaftMessage.Action.RequestVote) { // ignore
			 * any other requests } else if (action == RaftMessage.Action.Vote)
			 * { receiveVote(msg); } break;
			 * 
			 * }
			 */

			switch (action) {
			case Append:
				if (currentState == RState.Candidate) {
					if (msg.getTerm() >= term) {
						this.term = msg.getTerm();
						leaderID = msg.getOriginator();
						currentState = RState.Follower;
					}
				} else if (currentState == RState.Follower) {
					this.term = msg.getTerm();
					lastKnownBeat = System.currentTimeMillis();
					// TODO append work
				} else if (currentState == RState.Leader) {
					// should not happen
				}
				break;
			case RequestVote:
				if (currentState == RState.Candidate) {
					// candidate ignores other vote requests
				} else if (currentState == RState.Follower) {
					/**
					 * check if already voted for this term or else vote for the
					 * candidate
					 **/
					if (votedFor == null || msg.getTerm() > votedFor.getTerm()) {
						if(votedFor != null){
							System.out.println("Voting for "
									+ msg.getOriginator() + " for term"
									+ msg.getTerm() + " from node " + nodeId+" voted term "+ votedFor.getTerm());
						} else {
							System.out.println("Node "+nodeId+" Voting for first time for "+ msg.getOriginator());
						}
						
						votedFor = msg;
						voteForCandidate(msg);
					}
				} else if (currentState == RState.Leader) {
					// TODO
				}
				break;
			case Leader:
				if (msg.getTerm() > this.term) {
					leaderID = msg.getOriginator();
					this.term = msg.getTerm();
					lastKnownBeat = System.currentTimeMillis();
				}
				break;
			case Vote:
				if (currentState == RState.Candidate) {
					receiveVote(msg);
				} else if (currentState == RState.Follower) {

				} else if (currentState == RState.Leader) {

				}
				break;
			default:
			}

		}

		/** this is called by the Node's run() - reads from its inbox */
		@Override
		public void process(RaftMessage msg) {
			processReceivedMessage(msg);
		}

		public void setTransport(Bus<? extends RaftMessage> t) {
			this.transport = t;
		}
	}
}
