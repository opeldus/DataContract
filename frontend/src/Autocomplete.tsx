import { type ClipboardEvent, useEffect, useId, useRef, useState } from "react";

export interface Suggestion {
  value: string;
  hint?: string;
}

interface Props {
  value: string;
  disabled?: boolean;
  invalid?: boolean;
  label: string;
  placeholder?: string;
  search: (query: string) => Promise<Suggestion[]>;
  onChange: (value: string) => void;
  onPaste?: (event: ClipboardEvent<HTMLInputElement>) => void;
}

const SEARCH_DELAY_MS = 150;

export function Autocomplete({ value, disabled, invalid, label, placeholder, search, onChange, onPaste }: Props) {
  const [open, setOpen] = useState(false);
  const [suggestions, setSuggestions] = useState<Suggestion[]>([]);
  const [active, setActive] = useState(-1);
  const listId = useId();
  const latestQuery = useRef(0);

  useEffect(() => {
    if (!open) {
      return;
    }
    const queryId = ++latestQuery.current;
    const timer = window.setTimeout(() => {
      search(value)
        .then((result) => {
          if (queryId === latestQuery.current) {
            setSuggestions(result);
            setActive(-1);
          }
        })
        .catch(() => setSuggestions([]));
    }, SEARCH_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [open, value, search]);

  function choose(suggestion: Suggestion) {
    onChange(suggestion.value);
    setOpen(false);
  }

  return (
    <div className="autocomplete">
      <input
        role="combobox"
        aria-label={label}
        aria-expanded={open && suggestions.length > 0}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-invalid={invalid}
        autoComplete="off"
        disabled={disabled}
        placeholder={placeholder}
        value={value}
        onChange={(event) => {
          onChange(event.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onPaste={onPaste}
        onKeyDown={(event) => {
          if (event.key === "ArrowDown") {
            event.preventDefault();
            setOpen(true);
            setActive((index) => Math.min(index + 1, suggestions.length - 1));
          } else if (event.key === "ArrowUp") {
            event.preventDefault();
            setActive((index) => Math.max(index - 1, 0));
          } else if (event.key === "Enter" && open && suggestions[active]) {
            event.preventDefault();
            choose(suggestions[active]);
          } else if (event.key === "Escape") {
            setOpen(false);
          }
        }}
      />
      {open && suggestions.length > 0 && (
        <ul id={listId} role="listbox" className="suggestions">
          {suggestions.map((suggestion, index) => (
            <li
              key={suggestion.value}
              role="option"
              aria-selected={index === active}
              className={index === active ? "active" : undefined}
              onMouseDown={(event) => {
                event.preventDefault();
                choose(suggestion);
              }}
            >
              <span>{suggestion.value}</span>
              {suggestion.hint && <small>{suggestion.hint}</small>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
