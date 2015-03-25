package gash.leaderelection.raft;

import gash.leaderelection.raft.Raft.RaftNode;

public class RaftMain {
	public static void main(String[] args) throws InterruptedException {
		Raft raft = new Raft();
		final RaftNode<RaftMessage> node1 = new RaftNode<RaftMessage>(0, 3);
		RaftNode<RaftMessage> node2 = new RaftNode<RaftMessage>(1, 5);
		RaftNode<RaftMessage> node3 = new RaftNode<RaftMessage>(2, 5);
		RaftNode<RaftMessage> node4 = new RaftNode<RaftMessage>(3, 5);
		RaftNode<RaftMessage> node5 = new RaftNode<RaftMessage>(4, 5);
		
		raft.addNode(node1);
		raft.addNode(node2);
		raft.addNode(node3);
		raft.addNode(node4);
		raft.addNode(node5);
	
//		Thread.sleep(20000);
//		System.out.println("Stoppinf 0");
//		node1.stop();
	}
}
