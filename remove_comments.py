import os
import re

def is_formatting(comment_text):
    # If the comment is mostly symbols, it's formatting.
    alnum_count = sum(1 for c in comment_text if c.isalnum())
    non_alnum_count = sum(1 for c in comment_text if not c.isalnum() and not c.isspace())
    if non_alnum_count > alnum_count and non_alnum_count > 5:
        return True
    return False

def is_useless(comment_text):
    # Check for specific artifacts we want to remove
    bad_chars = ['\ufffd', '']
    for b in bad_chars:
        if b in comment_text:
            return True
            
    # Count words
    words = [w for w in comment_text.split() if w.isalnum() or len(w) > 1]
    
    if len(words) > 7:
        return True
        
    return False

def process_java_file(filepath):
    with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
        content = f.read()

    result = []
    i = 0
    state = 'NORMAL'
    current_comment = ""

    while i < len(content):
        if state == 'NORMAL':
            if content[i] == '"':
                state = 'STRING'
                result.append(content[i])
            elif content[i] == "'":
                state = 'CHAR'
                result.append(content[i])
            elif content[i:i+2] == '//':
                state = 'LINE_COMMENT'
                current_comment = ""
                i += 1
            elif content[i:i+2] == '/*':
                state = 'BLOCK_COMMENT'
                current_comment = ""
                i += 1
            else:
                result.append(content[i])
        elif state == 'STRING':
            if content[i] == '\\':
                result.append(content[i])
                i += 1
                if i < len(content):
                    result.append(content[i])
            elif content[i] == '"':
                state = 'NORMAL'
                result.append(content[i])
            else:
                result.append(content[i])
        elif state == 'CHAR':
            if content[i] == '\\':
                result.append(content[i])
                i += 1
                if i < len(content):
                    result.append(content[i])
            elif content[i] == "'":
                state = 'NORMAL'
                result.append(content[i])
            else:
                result.append(content[i])
        elif state == 'LINE_COMMENT':
            if content[i] == '\n':
                # Strip out the ─ and ═ symbols the user hates
                clean_comment = current_comment.replace('─', '').replace('═', '')
                
                # if there is too much whitespace after removing symbols, clean it up
                clean_comment = re.sub(r'\s+', ' ', clean_comment).strip()

                if '────────' in current_comment or '════════' in current_comment: # If it had the big line, it's just formatting
                    if clean_comment and not is_useless(clean_comment):
                        result.append('// ' + clean_comment)
                    # otherwise don't append anything (remove it)
                else:
                    if is_formatting(current_comment):
                        result.append('//' + current_comment.replace('─', '-').replace('═', '-'))
                    elif not is_useless(current_comment):
                        # Keep short comments
                        if clean_comment:
                            result.append('// ' + clean_comment)
                
                result.append('\n')
                state = 'NORMAL'
            else:
                current_comment += content[i]
        elif state == 'BLOCK_COMMENT':
            if content[i:i+2] == '*/':
                clean_comment = current_comment.replace('─', '')
                if '────────' in current_comment:
                    pass # remove completely
                else:
                    if is_formatting(current_comment):
                        result.append('/*' + current_comment.replace('─', '-') + '*/')
                    elif not is_useless(current_comment):
                        if clean_comment.strip():
                            stripped = clean_comment.strip()
                            stripped = re.sub(r'\s+', ' ', stripped)
                            result.append('/* ' + stripped + ' */')
                i += 1
                state = 'NORMAL'
            else:
                current_comment += content[i]
        
        i += 1

    # Clean up empty lines
    text = "".join(result)
    lines = text.split('\n')
    cleaned_lines = []
    empty_streak = 0
    for line in lines:
        if line.strip() == '':
            empty_streak += 1
            if empty_streak <= 1:
                cleaned_lines.append(line)
        else:
            empty_streak = 0
            cleaned_lines.append(line)
            
    text = '\n'.join(cleaned_lines)
    
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(text)

def main():
    root_dir = r"c:\Users\danie\Projects\GenPvP\src\main\java"
    count = 0
    for subdir, dirs, files in os.walk(root_dir):
        for file in files:
            if file.endswith('.java'):
                filepath = os.path.join(subdir, file)
                process_java_file(filepath)
                count += 1
    print(f"Processed {count} java files.")

if __name__ == '__main__':
    main()
