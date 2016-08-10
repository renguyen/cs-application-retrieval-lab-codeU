package com.flatironschool.javacs;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import javax.imageio.ImageIO;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import org.jsoup.select.Elements;

import java.util.HashSet;
import java.util.Map.Entry;
import redis.clients.jedis.Jedis;


/**
 * Represents the results of a search query.
 *
 */
public class WikiSearch {

	// map from URLs that contain the term(s) to relevance score
	private Map<String, Integer> map;

	private static Set<String> ignoreWords; 
	final static WikiFetcher wf = new WikiFetcher();

	/**
	 * Constructor.
	 *
	 * @param map
	 */
	public WikiSearch(Map<String, Integer> map) {
		this.map = map;  
	}

	/**
	 * Looks up the relevance of a given URL.
	 *
	 * @param url
	 * @return
	 */
	public Integer getRelevance(String url) {
		Integer relevance = map.get(url);
		return relevance==null ? 0: relevance;
	}

	/**
	 * Prints the contents in order of term frequency.
	 *
	 * @param map
	 */
	private JComponent print(long startTime, JedisIndex index, JComponent panel) {
		long duration = System.currentTimeMillis() - startTime;
		double MS_PER_SEC = 1000.0;
		List<Entry<String, Integer>> entries = sort();
		if (entries.isEmpty()) {
			System.out.println("No results found.");
		} else {
			for (Entry<String, Integer> entry: entries) {
				System.out.println(entry); 
   
				panel.add(createLabel(entry.getKey(), Color.white, new Font("Helvetica", Font.BOLD, 30)));
				panel.add(createLabel(Integer.toString(entry.getValue()), Color.white, new Font("Helvetica", Font.BOLD, 10)));
				String startingWords = index.getStartingWords(entry.getKey());
				if (startingWords != null) System.out.println(startingWords);
				if (startingWords != null)panel.add(createLabel(startingWords, Color.lightGray, new Font("Helvetica", Font.BOLD, 10)));
				panel.add(new JLabel(" "));
			}
		}
		panel.add(new JLabel("\nSearch took " + duration/MS_PER_SEC + "s."));
//		System.out.println("\nSearch took " + duration/MS_PER_SEC + "s.");
		return panel; 
	}

	public HashSet<String> getUrls() {
		return new HashSet(map.keySet());
	}

	/**
	 * Computes the union of two search results.
	 *
	 * @param that
	 * @return New WikiSearch object.
	 */
	public WikiSearch or(WikiSearch that) {
		Map<String, Integer> union = new HashMap<String, Integer>();
		HashSet<String> urls = new HashSet<String>(map.keySet());
		urls.addAll(that.getUrls());
		for (String url : urls) {
			union.put(url, totalRelevance(getRelevance(url), that.getRelevance(url)));
		}
		return new WikiSearch(union);
	}

	/**
	 * Computes the intersection of two search results.
	 *
	 * @param that
	 * @return New WikiSearch object.
	 */
	public WikiSearch and(WikiSearch that) {
		Map<String, Integer> intersection = new HashMap<String, Integer>();
		for (String url : map.keySet()) {
			if (getRelevance(url) != 0 && that.getRelevance(url) != 0) {
				intersection.put(url, totalRelevance(getRelevance(url), that.getRelevance(url)));
			} else {
				intersection.put(url, 0);
			}
		}
		return new WikiSearch(intersection);
	}

	/**
	 * Computes the intersection of two search results.
	 *
	 * @param that
	 * @return New WikiSearch object.
	 */
	public WikiSearch minus(WikiSearch that) {
		Map<String, Integer> intersection = new HashMap<String, Integer>();
		for (String url : map.keySet()) {
			if (that.getRelevance(url) == 0) intersection.put(url, getRelevance(url));
		}
		return new WikiSearch(intersection);
	}

	/**
	 * Computes the relevance of a search with multiple terms.
	 *
	 * @param rel1: relevance score for the first search
	 * @param rel2: relevance score for the second search
	 * @return
	 */
	protected int totalRelevance(Integer rel1, Integer rel2) {
		// simple starting place: relevance is the sum of the term frequencies.
		return rel1 + rel2;
	}

	/**
	 * Sort the results by relevance.
	 *
	 * @return List of entries with URL and relevance.
	 */
	public List<Entry<String, Integer>> sort() {
		List<Entry<String, Integer>> results = new LinkedList<Entry<String, Integer>>(map.entrySet());
		Comparator<Entry<String, Integer>> comparator = new Comparator<Entry<String, Integer>>() {
			@Override
			public int compare(Entry<String, Integer> entry1, Entry<String, Integer> entry2) {
				if (entry1.getValue() < entry2.getValue()) return -1;
				if (entry1.getValue() > entry2.getValue()) return 1;
				return 0;
			}
		};
		Collections.sort(results, comparator);
		return results;
	}

	/**
	 * Performs a search and makes a WikiSearch object.
	 *
	 * @param term
	 * @param index
	 * @return
	 */
	public static WikiSearch search(String term, JedisIndex index) {
		Map<String, Integer> map = index.getCounts(term);
		return new WikiSearch(map);
	}

	/**
	 * Helper function that returns index of first actual search term (doesn't include/
	 * isn't an operator) of the user inputted arguments starting from the given index
	 * @param startIndex which index to start searching
	 * @param excludeTerms list of terms that are to be excluded
	 * @param args user inputted terms
	 * @return
	 */
	private static int getNextTermIndex(int startIndex, ArrayList<String> excludeTerms, String[] args) {
		for (int i = startIndex; i < args.length; i++) {
			if (args[i].charAt(0) == '-') {
				excludeTerms.add(args[i]);
			} else if (isNotOperator(args[i])) {
				return i;
			}
		}

		return -1;
	}

	/**
	 * Checks if given term is AND or OR
	 * @param term
	 * @return true if term = AND/OR
	 */
	private static boolean isNotOperator(String term) {
		return (!term.equals("AND") && !term.equals("OR"));
	}


	private static WikiSearch getSearchResults(int currIndex, ArrayList<String> excludeTerms, String[] args, long startTime, JedisIndex index) throws IOException {
		WikiSearch results;
		results = search(args[currIndex], index);
		currIndex++;
		while (currIndex < args.length) {
			String currTerm = args[currIndex];
			if (currTerm.charAt(0) == '-') {
				excludeTerms.add(currTerm);
			} else {
				if (isNotOperator(currTerm)) {
					if(!ignoreWords.contains(currTerm.toLowerCase())) {
						WikiSearch otherSearch = search(currTerm, index);
						results = results.and(otherSearch);
					}
				} else {
					currIndex = getNextTermIndex(currIndex, excludeTerms, args);
					if (currIndex == -1) break;
					String otherTerm = args[currIndex]; 

					if (!ignoreWords.contains(otherTerm.toLowerCase())) {
						WikiSearch otherSearch = search(otherTerm, index);
						if (currTerm.equals("AND")) {
							results = results.and(otherSearch);
						} else {
							results = results.or(otherSearch);
						}
					}
				}		
			}
			currIndex++;
		}

		for (String term : excludeTerms) {
			WikiSearch otherSearch = search(term, index);
			results = results.minus(otherSearch);
		}

		if (results != null) {
			return results; 
		} 
		return null;
		
	}

	/**
	 * Function that reads in an inputted text file of words that search can ignore 
	 * and returns them in an arraylist 
	 */
	public static void setIgnoreWords() {
		ignoreWords = new HashSet<String>(); 
		String slash = File.separator;
		String fileName = "resources" + slash + "ignoreWords.txt";
		URL fileURL = WikiSearch.class.getClassLoader().getResource(fileName);
		String line = null;

		try {
			BufferedReader bufferedReader = new BufferedReader(new FileReader(fileURL.getFile()));
			line = bufferedReader.readLine(); 

			while(line != null) {
				ignoreWords.add(line.toLowerCase());
				line = bufferedReader.readLine(); 
			}   
			bufferedReader.close();  
		} catch(FileNotFoundException ex) {
			System.out.println( "Unable to open file '" + fileName + "'");                
		} catch(IOException ex) {
			System.out.println("Error reading file '" + fileName + "'");                  
		}

	}

	public static Set<String> getIgnoreWords() {
		return ignoreWords; 
	}

	public static void crawlAll(JedisIndex index) throws IOException {
		String[] articles = { "Awareness", "Computer_science", "Concurrent_computing", 
				"Consciousness", "Java_(programming_language)", "Knowledge",
				"Mathematics", "Modern_philosophy", "Philosophy", "Programming_language", 
				"Property_(philosophy)", "Quality_(philosophy)", "Science" };

		for (String article : articles) {
			String url = "https://en.wikipedia.org/wiki/" + article;

			WikiCrawler wc = new WikiCrawler(url, index);

			// for testing purposes, load up the queue
			Elements paragraphs = wf.fetchWikipedia(url);
			wc.queueInternalLinks(paragraphs);

			// loop until we index a new page
			String res;
			do {
				res = wc.crawl(true);
			} while (res == null);
		}
	}

	public static JFrame createFrame() {
		JFrame frame = new JFrame("Beam: A New Dawn on Search");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setSize(500, 500);
		frame.setResizable(true);
		frame.setLocationRelativeTo(null);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		return frame; 
	}
	
	public static JComponent createPanel(int height, int width) {
		JComponent content = new JPanel();
		content.setBackground(Color.gray);
		content.setVisible(true);
		content.setSize(width, height);
		return content; 
	}
	
	public static JLabel createLabel(String text, Color color, Font font) {
		JLabel label = new JLabel(text);
		label.setForeground(color);
		label.setFont(font);
		return label;
	}
	
	public static String[] getSearchTerms(JTextField searchText) {
		StringTokenizer search = new StringTokenizer(searchText.getText());
		String[] searchTerms = new String[search.countTokens()];
		int counter = 0; 
		while (search.hasMoreTokens()) {
	         searchTerms[counter] = search.nextToken();
	         counter++;
	     }
		return searchTerms; 
	}
	
	public static JButton getBackButton() {
		JButton backButton = new JButton("Back");
		backButton.setBackground(new Color(255, 216, 42));
		backButton.setForeground(Color.white);
		backButton.setOpaque(true);
		backButton.setBorderPainted(false);
		return backButton;
	}
	
	public static JButton getHomeSearchButton(final JTextField searchText, final ArrayList<String> excludeTerms,
												final JedisIndex index, final JFrame frame) {
		JButton searchButton = new JButton("Search!");
		searchButton.setBackground(new Color(255, 216, 42));
		searchButton.setForeground(Color.white);
		searchButton.setOpaque(true);
		searchButton.setBorderPainted(false);
		searchButton.addActionListener(new ActionListener() { 
			public void actionPerformed(ActionEvent e) { 
				
				String[] searchTerms = getSearchTerms(searchText); 
				long startTime = System.currentTimeMillis();
				int currIndex = getNextTermIndex(0, excludeTerms, searchTerms);
				
				if (currIndex != -1) {
					
					try {
						WikiSearch results = getSearchResults(currIndex, excludeTerms, searchTerms, startTime, index);
						JComponent overallPanel = new JPanel();

						JComponent newPanel = createPanel(1000, 500);

						newPanel.setLayout(new BoxLayout(newPanel, BoxLayout.Y_AXIS));
						newPanel.add(createLabel("Search results:", Color.white, new Font("Helvetica", Font.BOLD, 100)));
						newPanel = results.print(startTime, index, newPanel);
						
						JScrollPane scrollPane = new JScrollPane(newPanel);
						scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
						scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
						scrollPane.setSize(frame.getWidth(), frame.getHeight());
						overallPanel.add(scrollPane);
						frame.setContentPane(scrollPane);
						frame.setSize(new Dimension(1000, 500));
						frame.setVisible(true);
						
						
					} catch (IOException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
				} else {
					frame.getContentPane().add(new JLabel("Please enter at least one valid search term"));
				}
			
			} 
		} );

		return searchButton; 
	}
	
	public static void createDisplay() throws IOException, FontFormatException {
		
		final ArrayList<String> excludeTerms = new ArrayList<String>();
		setIgnoreWords(); 
		final WikiSearch results = null;

		Jedis jedis = JedisMaker.make();
		final JedisIndex index = new JedisIndex(jedis);
		
		final JFrame frame = createFrame(); 
		JComponent content = createPanel(500, 500); 
		content.setLayout(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		frame.add(content);
		
		JLabel welcomeLabel = createLabel("BEAM!", Color.white, new Font("Helvetica", Font.BOLD, 100)); 
		c.fill = GridBagConstraints.HORIZONTAL;
		c.ipady = 40;      //make this component tall
		c.weightx = 0.0;
		c.gridwidth = 3;
		c.gridx = 0;
		c.gridy = 0;
		content.add(welcomeLabel, c);


		JPanel functionalityPanel = new JPanel();
		functionalityPanel.setBackground(Color.gray);
		functionalityPanel.setLayout(new GridBagLayout());
		final JTextField searchText = new JTextField(18); 

		JButton searchButton = getHomeSearchButton(searchText, excludeTerms, index, frame); 
		
		
		functionalityPanel.add(searchText);
		functionalityPanel.add(new JLabel(" "));
		functionalityPanel.add(new JLabel(" "));
		functionalityPanel.add(new JLabel(" "));
		functionalityPanel.add(searchButton);
		c.fill = GridBagConstraints.HORIZONTAL;
		c.ipady = 70;      //make this component tall
		c.weightx = 0.0;
		c.gridwidth = 3;
		c.gridx = 1;
		c.gridy = 2;
		content.add(functionalityPanel, c);
		frame.setVisible(true);
	}

	public static void main(String[] args) throws IOException, FontFormatException {		
		createDisplay(); 
	}
}		
