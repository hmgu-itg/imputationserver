package genepi.imputationserver.util;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.RandomStringUtils;

public class PasswordCreator {

    private String spec_char_string="#!.,;:_+-%$?~=@";

    public static String createPassword() {
	return createPassword(8,8,4,4,5);
    }

    public static String createPassword(int uppercaseLetters, int lowercaseLetters, int numbers, int symbols, int duplicates) {
	String pwd = null;
	do {
	    String ucStr = RandomStringUtils.random(uppercaseLetters, 0, 0, true, false, null, new SecureRandom()).toUpperCase();
	    String lcStr = RandomStringUtils.random(lowercaseLetters, 0, 0, true, false, null, new SecureRandom()).toLowerCase();
	    String numStr = RandomStringUtils.random(numbers, 0, 0, false, true, null, new SecureRandom());
	    String symStr = RandomStringUtils.random(symbols,0,spec_char_string.length(),false,false,spec_char_string.toCharArray(),new SecureRandom());
	    pwd = shuffleAndCheck(ucStr+lcStr+numStr+symStr,duplicates);
	} while (pwd == null);

	return pwd;
    }

    private static String shuffleAndCheck(String input, int duplicates) {
	List<Character> characters = new ArrayList<Character>();
	int countDuplicates = 0;
	for (char c : input.toCharArray()) {
	    if (characters.contains(c)) {
		countDuplicates++;
	    }
	    characters.add(c);
	}
	if (countDuplicates >= duplicates) {
	    return null;
	}

	StringBuilder pwd = new StringBuilder(input.length());
	while (characters.size() != 0) {
	    int randPicker = (int) (Math.random() * characters.size());
	    pwd.append(characters.remove(randPicker));
	}
	return pwd.toString();
    }
}
