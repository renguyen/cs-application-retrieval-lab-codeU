package com.flatironschool.javacs;

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
	private  void print(long startTime) {
		long duration = System.currentTimeMillis() - startTime;
		double MS_PER_SEC = 1000.0;
		List<Entry<String, Integer>> entries = sort();
		if (entries.isEmpty()) {
			System.out.println("No results found.");
		} else {
			for (Entry<String, Integer> entry: entries) {
				System.out.println(entry);
			}
		}
		System.out.println("\nSearch took " + duration/MS_PER_SEC + "s.");
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

	private static void getSearchResults(int currIndex, ArrayList<String> excludeTerms, String[] args, long startTime) throws IOException {
		Jedis jedis = JedisMaker.make();
		JedisIndex index = new JedisIndex(jedis);

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
			results.print(startTime);
		} 
	}

	/**
	 * Function that reads in an inputted text file of words that search can ignore 
	 * and returns them in an arraylist 
	*/
	public static Set<String> getIgnoreWords() {
		
		if (ignoreWords == null) {
			String slash = File.separator;
			String fileName = "resources" + slash + "ignoreWords.txt";
			URL fileURL = WikiSearch.class.getClassLoader().getResource(fileName);
			ignoreWords = new HashSet<String>();
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
		return ignoreWords; 
	}

	public static void main(String[] args) throws IOException {			
		ArrayList<String> excludeTerms = new ArrayList<String>();
		getIgnoreWords(); 
		long startTime = System.currentTimeMillis();
		
		int currIndex = getNextTermIndex(0, excludeTerms, args);
		if (currIndex != -1) {
			getSearchResults(currIndex, excludeTerms, args, startTime);
		} else {
			System.out.println("Please enter at least one valid search term.");
		}
	}
}		
