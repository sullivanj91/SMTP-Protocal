import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;

public class Server {
	String curFromPath;
	String curError;
	DataOutputStream curOutput;
	//need to clear out this list somehow after message goes through or RCPT/data commands fail
	List<String> curRCPTpaths;
	List<String> curMessage;
	
	//enum class to maintain state
	public enum States {
		FROM, TO, DATA, MESSAGE
	}

	/**
	 * @param args
	 * @throws Exception 
	 */
	public static void main(String[] args) throws Exception {
		int portNum = Integer.parseInt(args[0]);
		//create server socket using command line port number
		ServerSocket welcomeSocket = new ServerSocket(portNum);
		String test = InetAddress.getLocalHost().getHostName();
		
		while(true){
			//waits for client to connect and then responds with greeting message
			Socket connectionSocket = welcomeSocket.accept();
			String greeting = "220 welcome to " + InetAddress.getLocalHost().getHostName() + '\n'; //might be wrong method
			
			//created buffered input and output streams for communication
			BufferedReader inFromClient = new BufferedReader(new InputStreamReader(connectionSocket.getInputStream()));
			DataOutputStream outToClient = new DataOutputStream(connectionSocket.getOutputStream());
			
			//send greeting and receive HELO response
			outToClient.writeBytes(greeting);
			String clientHELO = inFromClient.readLine();
			if(clientHELO.trim().substring(0, 4).equals("HELO")){//end index might be off
				String[] response = clientHELO.split("HELO");//check for HELO <DOMAIN> and HELO<DOMAIN>
				if(parseDomain(response[1].trim())){
					//domain is valid, acknowledge and begin processing message
					String valid = "250 " + clientHELO + '\n';
					outToClient.writeBytes(valid);
					processClientMessage(inFromClient, outToClient);
//					outToClient.writeBytes("221 server closing connection\n");
					connectionSocket.close();
					continue;
				}else{
					//domain was not valid send appropriate response to client and close
					String error = "501 Syntax error in parameters or arguments\n";
					System.out.println(error);
					outToClient.writeBytes(error);
					connectionSocket.close();
					continue;
				}
			}else{
				//HELO command was not valid send error to client and close
				String error = "500 Syntax error: command unrecognized\n";
				System.out.println(error);
				outToClient.writeBytes(error);
				connectionSocket.close();
				continue;
			}
		}
		

	}
	//method to parse Mail from and Rcpt commands
	public static boolean parseCMD(Server parser, String input, States curState){
		//will hold from path for later use in constructing file
		String tmpPath;
		String[] cmd = input.split(":");

		//first split input into two pieces and check mail from cmd or rcpt cmd
		if(curState == States.FROM){
			if(!cmd[0].equals("MAIL FROM") || cmd.length > 2){
				if(cmd[0].equals("RCPT TO") || cmd[0].equals("DATA")){
					parser.curError = "503 Bad sequence of commands";
					return false;
				}
				parser.curError = "500 Syntax error: command unrecognized";
				return false;
			}
		}else{
			if(!cmd[0].equals("RCPT TO") || cmd.length > 2){
				if(cmd[0].equals("MAIL FROM") || cmd[0].equals("DATA")){
					parser.curError = "503 Bad sequence of commands";
					return false;
				}
				parser.curError = "500 Syntax error: command unrecognized";
				return false;
			}
		}
		//now split the rest of the input by whitespace
		String[] tokens = cmd[1].split("\\s+");
		if(tokens.length > 2){
			parser.curError = "501 Syntax error in parameters or arguments";
			return false;
		}
		//variable to handle if there is no whitespace between FROM:<sender@com>
		int index = 1;
		if(tokens.length == 1){
			index = 0;
		}
		//check for '<' and '>' surrounding path and remove them
		if(!tokens[index].substring(0, 1).equals("<")){
			parser.curError = "501 Syntax error in parameters or arguments";
			return false;
		}
		tokens = tokens[index].split("<");
		if(tokens.length > 2){
			parser.curError = "501 Syntax error in parameters or arguments";
			return false;
		}
		if(!tokens[1].substring(tokens[1].length()-1).equals(">")){
			parser.curError = "501 Syntax error in parameters or arguments";
			return false;
		}		
		if(tokens[1].contains(">>")){
			parser.curError = "501 Syntax error in parameters or arguments";
			return false;
		}
		tokens = tokens[1].split(">");
		//store from-path temporarily, but continue checking syntax
		tmpPath = tokens[0];
		//now parse the mailbox
		tokens = tokens[0].split("@");
		if(tokens.length != 2){
			parser.curError = "501 Syntax error in parameters or arguments";
			return false;
		}
		//parse the local-part
		if(tokens[0].contains("<") || tokens[0].contains(">") || tokens[0].contains("(") || tokens[0].contains(")")
				|| tokens[0].contains("[") || tokens[0].contains("]") || tokens[0].contains("\"") || tokens[0].contains(".")
				|| tokens[0].contains(",") || tokens[0].contains(";") || tokens[0].contains(":") || tokens[0].contains("@") || tokens[0].contains("\\")){
			parser.curError = "501 Syntax error in parameters or arguments";
			return false;			
		}		
		//parse the domain		
		//handle first case when there is no '.'
		if(tokens[1].split("\\.").length == 1){
			//parse name
			if(!Character.isLetter(tokens[1].charAt(0))){
				parser.curError = "501 Syntax error in parameters or arguments";
				return false;
			}
			for(int i=1; i<tokens[1].length(); i++){
				if(!Character.isLetter(tokens[1].charAt(i)) && !Character.isDigit(tokens[1].charAt(i))){
					parser.curError = "501 Syntax error in parameters or arguments";
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
						parser.curError = "501 Syntax error in parameters or arguments";
						return false;
					}
					for(int i=1; i<tokens[j].length(); i++){
						if(!Character.isLetter(tokens[j].charAt(i)) && !Character.isDigit(tokens[j].charAt(i))){
							parser.curError = "501 Syntax error in parameters or arguments";
							return false;
						}
					}
				}else{
					parser.curError = "501 Syntax error in parameters or arguments";
					return false;
				}
			}			
		}//the domain has two '..' in a row
		else{
			parser.curError = "501 Syntax error in parameters or arguments";
			return false;
		}

		//if we get this far, should have a valid command
		//store the valid from-path in the instance to be used later in construction of file
		if(curState == States.FROM){
			parser.curFromPath = tmpPath;
		}else{
			parser.curRCPTpaths.add(tmpPath);
		}		
		return true;
	}
	//method to process client message
	public static void processClientMessage(BufferedReader inFromClient, DataOutputStream outToClient){
		String input;		
		//create instance of parser so parse methods can set instance variables
		Server parser = new Server();			
		//set current output stream to client
		parser.curOutput = outToClient;
		//maintain state of commands
		States curState = States.FROM;
		try {
			while((input=inFromClient.readLine())!=null){
				//returns to close connection if QUIT command recieved
				if(input.trim().equals("QUIT")){
					return;
				}
				//handle mail from command
				else if (curState == States.FROM){
					if(parseCMD(parser, input, curState)){
						parser.curRCPTpaths = new ArrayList<String>();
						parser.curMessage = new ArrayList<String>();
						curState = States.TO;
						parser.curOutput.writeBytes("250 OK\n");
					}else{
						System.out.println(parser.curError);
						parser.curOutput.writeBytes(parser.curError + '\n');
						return;
					}				
				}//parse first rcpt command
				else if(curState == States.TO){
					if(parseCMD(parser, input, curState)){
						curState = States.DATA;
						parser.curOutput.writeBytes("250 OK\n");
					}else{
						System.out.println(parser.curError);
						parser.curOutput.writeBytes(parser.curError + '\n');
						return;
					}
				}else if (curState == States.DATA){
					//parse data command
					if(input.trim().equals("DATA")){
						curState = States.MESSAGE;
						parser.curOutput.writeBytes("354 Start mail input; end with <CRLF>.<CRLF>\n");
					}//parse multiple rcpt commands
					else{
						if(parseCMD(parser, input, curState)){
							curState = States.DATA;
							parser.curOutput.writeBytes("250 OK\n");
						}else{
							System.out.println(parser.curError);
							parser.curOutput.writeBytes(parser.curError + '\n');
							return;
						}
					}
				}else if(curState == States.MESSAGE){
					if(input.equals(".")){
						//since we can assume each rcpt will have same domain only need to create one file
						String rcpt = parser.curRCPTpaths.get(0);
						String domain = rcpt.split("@")[1]; //make sure right index
						String wkdir = System.getProperty("user.dir");
				    	File file = new File(wkdir + "/" + domain);			 
				    	//if file doesnt exists, then create it
				    	if(!file.exists()){
				    		file.createNewFile();
				    	}			 
				    	//true = append file
				    	FileWriter fileWritter = new FileWriter(file.getPath(),true);
				    	BufferedWriter bufferWritter = new BufferedWriter(fileWritter);
				    	bufferWritter.write("From: " + "<" + parser.curFromPath + ">");
				    	bufferWritter.newLine();
				        for(int j=0; j<parser.curRCPTpaths.size();j++){
				        	bufferWritter.write("To: " + "<" + parser.curRCPTpaths.get(j) + ">");
				        	bufferWritter.newLine();
				        }
				        for(int m=0; m<parser.curMessage.size();m++){
				        	bufferWritter.write(parser.curMessage.get(m));
				        	bufferWritter.newLine();
				        }
				        bufferWritter.close();
				        curState = States.FROM;
				        parser.curOutput.writeBytes("250 OK\n");
					}else{
						//store inputs to create message later
						parser.curMessage.add(input);
						parser.curOutput.writeBytes("250 OK\n");
					}
				}
				
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	//method to check if domain element is correctly formatted
	public static boolean parseDomain(String domain){
		String[] tokens;
		//handle first case when there is no '.'
		if(domain.split("\\.").length == 1){
			//parse name
			if(!Character.isLetter(domain.charAt(0))){
				return false;
			}
			for(int i=1; i<domain.length(); i++){
				if(!Character.isLetter(domain.charAt(i)) && !Character.isDigit(domain.charAt(i))){
					return false;
				}
			}			
		}//else the domain contains a '.'
		else if(domain.split("\\.").length != 0){
			//check all <element> strings are valid in domain
			tokens = domain.split("\\.");
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
		//if we get this far the domain is valid
		return true;
	}

}
