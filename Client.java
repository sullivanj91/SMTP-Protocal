import java.io.*;
import java.net.*;




public class Client {
	//enum class to maintain sate
	public enum States {
		START, FROM, TO, DATA, MESSAGE
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		//get hostname and port number from command line args
		String hostname = args[0];
		int portNum = Integer.parseInt(args[1]);
		
		//create client socket with hostname and port number
		try {
			Socket clientSocket = new Socket(hostname, portNum);
			//build streams to handle client/server communication
			DataOutputStream outToServer = new DataOutputStream(clientSocket.getOutputStream());
			BufferedReader inFromServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
			//Receive server welcome and send HELO command
			String serverWelcome = inFromServer.readLine();
			if(serverWelcome.trim().substring(0, 3).equals("220")){
				String response = "HELO " + InetAddress.getLocalHost().getHostName() + '\n';
				outToServer.writeBytes(response);
				if(waitForResponse(States.START, inFromServer)){
					sendOutgoingfile(outToServer, inFromServer);
					clientSocket.close();
					System.exit(1);
				}else{
					System.out.println("504 Bad Server Response");
					clientSocket.close();
					System.exit(1);
				}
			}else{
				System.out.println("500 Syntax error: command unrecognized");
				clientSocket.close();
				System.exit(1);
			}
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			System.out.println(e.getLocalizedMessage());
			System.exit(1);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			System.out.println(e.getLocalizedMessage());
			System.exit(1);
		}

	}
	//method to check syntax of outgoing file
	private static boolean checkCMD(String input, States curState){
		String[] cmd = input.split(":");

		//first split input into two pieces and check mail from cmd or rcpt cmd
		if(curState == States.FROM){
			if(!cmd[0].equals("From") || cmd.length > 2){
				return false;
			}
		}else{
			if(!cmd[0].equals("To") || cmd.length > 2){
				return false;
			}
		}
		//now split the rest of the input by whitespace
		String[] tokens = cmd[1].split("\\s+");
		if(tokens.length > 2){
			
			return false;
		}
		//variable to handle if there is no whitespace between FROM:<sender@com>
		int index = 1;
		if(tokens.length == 1){
			index = 0;
		}
		//check for '<' and '>' surrounding path and remove them
		if(!tokens[index].substring(0, 1).equals("<")){
			
			return false;
		}
		tokens = tokens[index].split("<");
		if(tokens.length > 2){
			
			return false;
		}
		if(!tokens[1].substring(tokens[1].length()-1).equals(">")){
			
			return false;
		}		
		if(tokens[1].contains(">>")){
			
			return false;
		}
		tokens = tokens[1].split(">");
		//now parse the mailbox
		tokens = tokens[0].split("@");
		if(tokens.length != 2){
			
			return false;
		}
		//parse the local-part
		if(tokens[0].contains("<") || tokens[0].contains(">") || tokens[0].contains("(") || tokens[0].contains(")")
				|| tokens[0].contains("[") || tokens[0].contains("]") || tokens[0].contains("\"") || tokens[0].contains(".")
				|| tokens[0].contains(",") || tokens[0].contains(";") || tokens[0].contains(":") || tokens[0].contains("@") || tokens[0].contains("\\")){
			
			return false;			
		}		
		//parse the domain		
		//handle first case when there is no '.'
		if(tokens[1].split("\\.").length == 1){
			//parse name
			if(!Character.isLetter(tokens[1].charAt(0))){
				
				return false;
			}
			for(int i=1; i<tokens[1].length(); i++){
				if(!Character.isLetter(tokens[1].charAt(i)) && !Character.isDigit(tokens[1].charAt(i))){
					
					return false;
				}
			}			
		}//else the domain contains a '.'
		else if(tokens[1].split("\\.").length != 0){
			//check all <element> strings are valid in domain
			tokens = tokens[1].split("\\.");
			for(int j=0; j<tokens.length; j++){
				//handle '..' in middle of domain
				if(!tokens[j].equals("")){
					if(!Character.isLetter(tokens[j].charAt(0))){
						
						return false;
					}
					for(int i=1; i<tokens[j].length(); i++){
						if(!Character.isLetter(tokens[j].charAt(i)) && !Character.isDigit(tokens[j].charAt(i))){
							
							return false;
						}
					}
				}else{
					
					return false;
				}
			}			
		}//the domain has two '..' in a row
		else{
			
			return false;
		}

		//if we get this far, should have a valid command
		return true;
	}
	private static void sendOutgoingfile(DataOutputStream outToServer, BufferedReader inFromServer){
		//get the working directory
		String wkdir = System.getProperty("user.dir");
		//read the outgoing file for messages
		try (BufferedReader br = new BufferedReader(new FileReader(wkdir + "/outgoing")))
		{

			String sCurrentLine;
			States curState = States.FROM;

			//read through each line in the file
			while ((sCurrentLine = br.readLine()) != null) {
				String[] tokens;
				//start with From: <reverse-path>
				if(curState == States.FROM){
					if(checkCMD(sCurrentLine, curState)){
						tokens = sCurrentLine.split("From:");
						tokens = tokens[1].split("\\s+");
						//get reverse path to send to server
						outToServer.writeBytes("MAIL FROM: " + tokens[1] + '\n');
						//wait for 250 response from server, if fails quit program
						if(waitForResponse(curState, inFromServer)){
							curState = States.TO;
						}else{							
							System.out.println("504 Bad Server Response");
							return;
						}
					}else{
						System.out.println("501 Syntax error in parameters or arguments");
						return;
					}
				}else if(curState == States.TO){
					if(checkCMD(sCurrentLine, curState)){
						//do exact same processing as above for To: <forward-path>
						tokens = sCurrentLine.split("To:");
						tokens = tokens[1].split("\\s+");
						outToServer.writeBytes("RCPT TO: " + tokens[1] + '\n');
						if(waitForResponse(curState, inFromServer)){
							curState = States.DATA;
						}else{							
							System.out.println("504 Bad Server Response");
							return;
						}
					}else{
						System.out.println("501 Syntax error in parameters or arguments");
						return;
					}
				}else if(curState == States.DATA){
					//handle multiple rcpt's
					if(sCurrentLine.trim().substring(0, 3).equals("To:")){
						if(checkCMD(sCurrentLine, curState)){
							tokens = sCurrentLine.split("To:");
							tokens = tokens[1].split("\\s+");
							outToServer.writeBytes("RCPT TO: " + tokens[1] + '\n');
							if(waitForResponse(States.TO, inFromServer)){
								curState = States.DATA;
							}else{								
								System.out.println("504 Bad Server Response");
								return;
							}
						}else{
							System.out.println("501 Syntax error in parameters or arguments");
							return;
						}
					}else{
						//now have processed rcpt to command so output DATA command
						outToServer.writeBytes("DATA\n");
						//wait for 354 response
						if(waitForResponse(curState, inFromServer)){
							outToServer.writeBytes(sCurrentLine + '\n');
							if(waitForResponse(States.MESSAGE, inFromServer)){
								curState = States.MESSAGE;
							}else{
								
								System.out.println("504 Bad Server Response");
								return;
							}
						}else{
							
							System.out.println("504 Bad Server Response");
							return;
						}
					}
				}else if (curState == States.MESSAGE){
					//data command has been received successfully so send each line of message
					//first check for "From:" denoting a new email to be parsed
					if(sCurrentLine.substring(0, 5).equals("From:")){
						//means we have finished outputting first message so tell server with "." 
						outToServer.writeBytes(".\n");
						if(waitForResponse(curState, inFromServer)){
							//means message was received by server begin parsing the next email
							curState = States.FROM;
							tokens = sCurrentLine.split("From:");
							tokens = tokens[1].split("\\s+");
							//get reverse path to print out
							outToServer.writeBytes("MAIL FROM: " + tokens[1] + '\n');
							//wait for 250 response from server, if fails quit program
							if(waitForResponse(curState, inFromServer)){
								curState = States.TO;
							}else{
								
								System.out.println("504 Bad Server Response");
								return;
							}
						}else{
							
							System.out.println("504 Bad Server Response");
							return;
						}
					}else{
						//continue to print out contents of message
						outToServer.writeBytes(sCurrentLine + '\n');
						if(waitForResponse(curState, inFromServer)){
							curState = States.MESSAGE;
						}else{
							
							System.out.println("504 Bad Server Response");
							return;
						}
					}
				}
			}
			//have reached end of file input so last email has been sent to server
			//need to let server know message is complete with "."
			outToServer.writeBytes(".\n");
			//either way emit quit command and exit
			if(waitForResponse(States.MESSAGE, inFromServer)){
				outToServer.writeBytes("QUIT\n");
				return;
			}else{
				
				System.out.println("504 Bad Server Response");
				return;
			}

		} catch (IOException e) {
			System.out.println(e.getLocalizedMessage());
			System.exit(1);
		} catch(ArrayIndexOutOfBoundsException  e){
			System.out.println(e.getLocalizedMessage());
			System.exit(1);
		}
	}
	private static boolean waitForResponse(States curState, BufferedReader inFromServer){
		try{	 
			String input;
	 
			while((input=inFromServer.readLine())!=null){
				String[] tokens;
				//handles mail-from, rcpt-to, and "." responses since they are the same
				if(curState == States.FROM || curState == States.TO || curState == States.MESSAGE || curState == States.START){
					tokens = input.split("\\s+");
					if(tokens[0].equals("250")){
						return true;
					}else{
						return false;
					}
				}else if(curState == States.DATA){
					//waits for data command 354 response
					tokens = input.split("\\s+");
					if(tokens[0].equals("354")){
						return true;
					}else{
						return false;
					}
				}
			}
	 
		}catch(IOException io){
			System.out.println(io.getLocalizedMessage());
			System.exit(1);
		}
		return false;		
	}

}
